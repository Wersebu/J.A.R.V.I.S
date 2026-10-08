package com.jarvis.tools.planner;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An agent's working plan for one conversation.
 *
 * @param conversationId conversation id
 * @param goal overall goal
 * @param steps ordered steps
 * @param createdAt creation time
 * @param updatedAt last change
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskPlan(String conversationId, String goal, List<Step> steps, String createdAt, String updatedAt) {

    public TaskPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    /**
     * One plan step.
     *
     * @param number 1-based number
     * @param title what to do
     * @param status current status
     * @param note result/blocker note
     */
    public record Step(int number, String title, TaskPlanService.StepStatus status, String note) {
    }

    /**
     * Whether every step reached a terminal status.
     *
     * @return finished flag
     */
    @JsonIgnore
    public boolean isFinished() {
        return steps.stream().allMatch(step -> step.status() == TaskPlanService.StepStatus.DONE
                || step.status() == TaskPlanService.StepStatus.SKIPPED
                || step.status() == TaskPlanService.StepStatus.BLOCKED);
    }

    /**
     * Returns the step currently in progress, else the first pending one.
     *
     * @return next step
     */
    @JsonIgnore
    public Optional<Step> nextStep() {
        return steps.stream().filter(step -> step.status() == TaskPlanService.StepStatus.IN_PROGRESS).findFirst()
                .or(() -> steps.stream().filter(step -> step.status() == TaskPlanService.StepStatus.PENDING).findFirst());
    }

    /**
     * Counts steps with the given status.
     *
     * @param status status
     * @return count
     */
    public long count(TaskPlanService.StepStatus status) {
        return steps.stream().filter(step -> step.status() == status).count();
    }

    /**
     * Renders a compact checklist for prompts and tool results.
     *
     * @return checklist text
     */
    @JsonIgnore
    public String render() {
        StringBuilder text = new StringBuilder();
        text.append("Goal: ").append(goal == null || goal.isBlank() ? "(not stated)" : goal).append('\n');
        for (Step step : steps) {
            String mark = switch (step.status()) {
                case DONE -> "[x]";
                case IN_PROGRESS -> "[>]";
                case BLOCKED -> "[!]";
                case SKIPPED -> "[-]";
                case PENDING -> "[ ]";
            };
            text.append(mark).append(' ').append(step.number()).append(". ").append(step.title());
            if (step.note() != null && !step.note().isBlank()) {
                text.append(" — ").append(step.note());
            }
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * Converts the plan to event/tool-result data.
     *
     * @return map view
     */
    @JsonIgnore
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("goal", goal == null ? "" : goal);
        map.put("steps", steps.stream().map(step -> Map.<String, Object>of(
                "number", step.number(),
                "title", step.title(),
                "status", step.status().name(),
                "note", step.note() == null ? "" : step.note())).toList());
        map.put("done", count(TaskPlanService.StepStatus.DONE));
        map.put("total", steps.size());
        map.put("finished", isFinished());
        nextStep().ifPresent(step -> map.put("nextStep", step.number() + ". " + step.title()));
        return map;
    }
}
