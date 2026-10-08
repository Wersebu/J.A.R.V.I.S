package com.jarvis.tools.runtime;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sub-agents: runs a self-contained task in a separate tool loop with a fresh context and returns
 * only its report. The main loop stays small (a big codebase search does not flood it), the same
 * way a coding agent delegates exploration to a helper agent.
 */
@Service
public class SubagentTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(SubagentTool.class);
    private static final int MAX_REPORT_CHARS = 8_000;
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final ObjectProvider<NativeToolLoopService> loop;

    /**
     * Creates the tool.
     *
     * @param loop native tool loop (resolved lazily - it depends on the tool catalog itself)
     */
    public SubagentTool(ObjectProvider<NativeToolLoopService> loop) {
        this.loop = loop;
    }

    @Override
    public String getName() {
        return "agent";
    }

    @Override
    public String getDescription() {
        return "Delegate a self-contained sub-task to a helper agent with a fresh context; it uses the same tools "
                + "and returns only a concise report.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(getName(), getDescription(), List.of(
                new ToolOperationDefinition("RUN",
                        "Run a helper agent. Use it for broad searches/analysis whose raw output would be large (e.g. "
                                + "'find where login is handled in this project and summarize the flow with file:line "
                                + "references', 'read these 10 files and list every TODO'), or for an independent sub-task. "
                                + "Write the task so it stands alone: what to do, where, and what the report must contain. "
                                + "The helper cannot ask the user questions and cannot start further helpers.",
                        List.of(new ToolArgumentDefinition("task", "string", true, "Complete, standalone task description"),
                                new ToolArgumentDefinition("expectedReport", "string", false,
                                        "What the report should contain, e.g. 'file paths with line numbers and a 5-line summary'")),
                        false, ToolSafetyLevel.READ)
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        ToolCallingRequest parent = NativeToolLoopService.currentRequest();
        if (parent == null) {
            return failure(request, "Helper agents can only run inside an agent turn.");
        }
        if (DEPTH.get() > 0) {
            return failure(request, "A helper agent cannot start another helper agent - do the work directly.");
        }
        String task = String.valueOf(request.arguments().getOrDefault("task", "")).strip();
        if (task.isBlank()) {
            return failure(request, "task is required");
        }
        String expected = String.valueOf(request.arguments().getOrDefault("expectedReport", "")).strip();
        int number = COUNTER.incrementAndGet();
        Map<String, Object> context = new LinkedHashMap<>(parent.context());
        context.put("subagent", true);
        String goal = task + (expected.isBlank() ? "" : "\n\nThe final report must contain: " + expected)
                + "\n\nYou are a helper agent: do the work with tools, then reply with a concise, factual report "
                + "(include file paths/line numbers where relevant). Do not ask questions.";
        ToolCallingRequest child = new ToolCallingRequest(
                parent.requestId() + "-agent" + number,
                parent.conversationId() + "#agent" + number,
                goal,
                goal,
                "Delegated by the main agent",
                context,
                parent.basePrompt(),
                parent.brain(),
                parent.knowledgeMode(),
                List.of(),
                ""
        );
        LOGGER.info("[SUBAGENT] start parentRequestId={} agent={} task=\"{}\"", parent.requestId(), number,
                task.length() > 200 ? task.substring(0, 200) + "..." : task);
        DEPTH.set(DEPTH.get() + 1);
        ToolCallingResult result;
        try {
            NativeToolLoopService service = loop.getIfAvailable();
            if (service == null) {
                return failure(request, "Helper agents are not available in this Core build.");
            }
            result = service.execute(child);
        } catch (RuntimeException exception) {
            return failure(request, "Helper agent failed: " + exception.getMessage());
        } finally {
            DEPTH.set(DEPTH.get() - 1);
            NativeToolLoopService.restoreCurrentRequest(parent);
        }
        String report = result.finalAnswer() == null ? "" : result.finalAnswer().strip();
        if (report.length() > MAX_REPORT_CHARS) {
            report = report.substring(0, MAX_REPORT_CHARS) + "\n...[report truncated]";
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("report", report.isBlank() ? "(the helper agent produced no report)" : report);
        data.put("toolCalls", result.steps().size());
        LOGGER.info("[SUBAGENT] finish agent={} toolSteps={} reportChars={}", number, result.steps().size(), report.length());
        return new ToolResult(!report.isBlank(), getName(), "RUN", request.requestId(), request.conversationId(), false,
                List.of(), "Helper agent report", data, report.isBlank() ? "SUBAGENT_NO_REPORT" : "", "", false, "");
    }

    private ToolResult failure(ToolRequest request, String message) {
        return new ToolResult(false, getName(), "RUN", request.requestId(), request.conversationId(), false, List.of(),
                message, Map.of("error", message), "SUBAGENT_FAILED", message, false, "");
    }
}
