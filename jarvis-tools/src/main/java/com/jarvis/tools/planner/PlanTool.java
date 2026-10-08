package com.jarvis.tools.planner;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Model-facing planning tool (the equivalent of a coding agent's todo list).
 *
 * <p>For any multi-step task the model first writes a short plan, then works through it step by
 * step, marking each step done/blocked with a note, and only gives the final answer when the plan is
 * finished. Every change is streamed to the client as {@link CognitiveEventType#PLAN_UPDATED} so the
 * user sees live progress instead of being asked what to do next after each action.</p>
 */
@Service
public class PlanTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlanTool.class);
    private static final String TOOL_NAME = "plan";

    private final TaskPlanService planService;
    private final CognitiveEventBus eventBus;

    /**
     * Creates the tool.
     *
     * @param planService plan storage
     * @param eventBus request event bus
     */
    public PlanTool(TaskPlanService planService, CognitiveEventBus eventBus) {
        this.planService = planService;
        this.eventBus = eventBus;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Your working task plan for this conversation. For any task needing 3+ actions: create a plan "
                + "first, then execute the steps yourself with the other tools, marking each step done as you go. "
                + "Do not ask the user for permission between steps - only stop for genuinely missing essential "
                + "information or a destructive action.";
    }

    @Override
    public ToolDefinition definition() {
        ToolJsonSchema stepsSchema = ToolJsonSchema.arrayOf(
                ToolJsonSchema.string("One concrete, verifiable step"),
                "Ordered steps, each a short concrete action (max 40)");
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                new ToolOperationDefinition("CREATE",
                        "Create (or replace) the plan for the current task: an overall goal plus ordered concrete steps. "
                                + "Step 1 starts automatically. Then immediately start executing step 1 with the real tools.",
                        List.of(new ToolArgumentDefinition("goal", "string", true, "What the user ultimately wants achieved"),
                                new ToolArgumentDefinition("steps", true, stepsSchema)),
                        false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("UPDATE_STEP",
                        "Mark a step done/blocked/skipped/in_progress with a short note about the result. Marking a step "
                                + "done automatically starts the next pending step - continue with it right away.",
                        List.of(new ToolArgumentDefinition("step", "integer", true, "1-based step number"),
                                new ToolArgumentDefinition("status", "string", true, "done, in_progress, blocked, skipped or pending"),
                                new ToolArgumentDefinition("note", "string", false, "Short result, decision taken or blocker")),
                        false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("ADD_STEPS",
                        "Append steps you discovered while working (e.g. a failing test that must be fixed).",
                        List.of(new ToolArgumentDefinition("steps", true, stepsSchema)),
                        false, ToolSafetyLevel.READ),
                new ToolOperationDefinition("GET",
                        "Show the current plan with step statuses (use when resuming an earlier task).",
                        List.of(), false, ToolSafetyLevel.READ)
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        String operation = request.operation() == null ? "" : request.operation().toUpperCase(Locale.ROOT);
        try {
            TaskPlan plan = switch (operation) {
                case "CREATE" -> planService.create(request.conversationId(),
                        text(request.arguments().get("goal")), list(request.arguments().get("steps")));
                case "UPDATE_STEP" -> planService.update(request.conversationId(),
                        integer(request.arguments().get("step")),
                        TaskPlanService.StepStatus.parse(text(request.arguments().get("status"))),
                        text(request.arguments().get("note")));
                case "ADD_STEPS" -> planService.addSteps(request.conversationId(), list(request.arguments().get("steps")));
                case "GET" -> planService.find(request.conversationId()).orElse(null);
                default -> throw new IllegalArgumentException("Unsupported plan operation: " + request.operation());
            };
            if (plan == null) {
                return result(request, operation, true, "No plan exists yet for this conversation.", Map.of("exists", false));
            }
            if (!"GET".equals(operation)) {
                publish(plan);
            }
            LOGGER.info("[PLANNER] requestId={} conversationId={} operation={} done={}/{}",
                    request.requestId(), request.conversationId(), operation,
                    plan.count(TaskPlanService.StepStatus.DONE), plan.steps().size());
            return result(request, operation, true, guidance(plan), plan.toMap());
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false,
                    List.of(), message, Map.of("error", message), "PLAN_INVALID", message, false, "");
        }
    }

    private String guidance(TaskPlan plan) {
        String checklist = plan.render();
        if (plan.isFinished()) {
            return checklist + "All steps are finished. Verify the result if not done yet, then give the final answer "
                    + "summarizing what was done (and any blocked steps).";
        }
        return checklist + plan.nextStep()
                .map(step -> "Now execute step " + step.number() + " (" + step.title() + ") with the appropriate tools. "
                        + "Do not stop or ask the user unless essential information is truly missing.")
                .orElse("");
    }

    private void publish(TaskPlan plan) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("plan", plan.toMap());
        metadata.put("conversationId", plan.conversationId());
        eventBus.publish(CognitiveEventType.PLAN_UPDATED, plan.isFinished() ? "FINISHED" : "RUNNING",
                plan.render(), null, metadata);
    }

    private ToolResult result(ToolRequest request, String operation, boolean success, String message, Map<String, Object> data) {
        return new ToolResult(success, TOOL_NAME, operation, request.requestId(), request.conversationId(),
                !"GET".equals(operation), List.of(), message, data, "", "", false, "");
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).strip();
    }

    private static int integer(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("step must be a step number");
        }
    }

    /**
     * Accepts a real JSON array, or (small local models often do this) one string with one step
     * per line or a numbered list.
     */
    static List<String> list(Object value) {
        List<String> steps = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            collection.forEach(item -> steps.add(text(item)));
        } else {
            for (String line : text(value).split("\\r?\\n|;")) {
                steps.add(line.replaceFirst("^\\s*(?:[-*•]|\\d+[.)])\\s*", "").strip());
            }
        }
        steps.removeIf(String::isBlank);
        return steps;
    }
}
