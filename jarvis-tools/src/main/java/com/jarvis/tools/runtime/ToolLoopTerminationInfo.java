package com.jarvis.tools.runtime;

import java.util.List;
import java.util.Map;

/**
 * Structured, testable account of how one native tool-loop execution ended - the data a diagnostic
 * message (Core logs, the chat pipeline's user-facing text, the desktop client's termination card)
 * is built from, instead of guessing the reason from a blank or garbled final answer.
 *
 * @param terminationReason why the loop stopped
 * @param completed whether the loop produced a real, verified final answer for the user's goal
 * @param goalSatisfied whether the goal contract was verified complete
 * @param configuredMaxTurns the effective model-turn budget in force when the loop stopped
 * @param usedModelTurns how many model turns actually ran
 * @param executedToolCalls how many native tool calls actually executed (blocked/rejected calls excluded)
 * @param successfulToolCalls how many of those executed calls succeeded
 * @param failedToolCalls how many of those executed calls failed
 * @param elapsedMs wall-clock time the loop ran for
 * @param lastToolName the tool of the most recent executed call, blank if none executed
 * @param lastToolOperation the operation of the most recent executed call, blank if none executed
 * @param lastErrorCode the error code of the most recent failure, blank if none
 * @param lastErrorMessage the error message of the most recent failure, blank if none
 * @param lastModelContent the model's own last piece of final-answer text, blank when it never produced one
 * @param nextRequiredAction a plain-text description of what would need to happen next, blank when nothing remains
 * @param remainingGoalCriteria goal-contract criteria not confirmed satisfied when the loop stopped
 * @param changesMade whether any successful tool call actually changed project/application state
 * @param verificationPerformed whether any successful tool call actually verified/tested the result
 */
public record ToolLoopTerminationInfo(
        ToolLoopTerminationReason terminationReason,
        boolean completed,
        boolean goalSatisfied,
        int configuredMaxTurns,
        int usedModelTurns,
        int executedToolCalls,
        int successfulToolCalls,
        int failedToolCalls,
        long elapsedMs,
        String lastToolName,
        String lastToolOperation,
        String lastErrorCode,
        String lastErrorMessage,
        String lastModelContent,
        String nextRequiredAction,
        List<String> remainingGoalCriteria,
        boolean changesMade,
        boolean verificationPerformed
) {

    /**
     * Normalizes null fields to safe defaults.
     */
    public ToolLoopTerminationInfo {
        terminationReason = terminationReason == null ? ToolLoopTerminationReason.UNKNOWN : terminationReason;
        lastToolName = lastToolName == null ? "" : lastToolName;
        lastToolOperation = lastToolOperation == null ? "" : lastToolOperation;
        lastErrorCode = lastErrorCode == null ? "" : lastErrorCode;
        lastErrorMessage = lastErrorMessage == null ? "" : lastErrorMessage;
        lastModelContent = lastModelContent == null ? "" : lastModelContent;
        nextRequiredAction = nextRequiredAction == null ? "" : nextRequiredAction;
        remainingGoalCriteria = remainingGoalCriteria == null ? List.of() : List.copyOf(remainingGoalCriteria);
    }

    /**
     * The neutral placeholder used where no real loop execution happened (e.g. native tool runtime
     * disabled, or no tool definitions resolved) - {@link ToolCallingResult}'s backward-compatible
     * constructor falls back to this so every result always carries a non-null termination info.
     *
     * @return an {@link ToolLoopTerminationReason#UNKNOWN} placeholder
     */
    public static ToolLoopTerminationInfo unknown() {
        return new ToolLoopTerminationInfo(ToolLoopTerminationReason.UNKNOWN, false, false, 0, 0, 0, 0, 0, 0L,
                "", "", "", "", "", "", List.of(), false, false);
    }

    /**
     * Wire/log-safe metadata view - every field except {@link #lastModelContent}, which can carry the
     * model's full final-answer text and must never be attached to a diagnostics event or log line
     * unconditionally.
     *
     * @return metadata map suitable for {@code CognitiveEvent#metadata()} or a log summary
     */
    public Map<String, Object> toMetadata() {
        return Map.ofEntries(
                Map.entry("terminationReason", terminationReason.name()),
                Map.entry("completed", completed),
                Map.entry("goalSatisfied", goalSatisfied),
                Map.entry("configuredMaxTurns", configuredMaxTurns),
                Map.entry("usedModelTurns", usedModelTurns),
                Map.entry("executedToolCalls", executedToolCalls),
                Map.entry("successfulToolCalls", successfulToolCalls),
                Map.entry("failedToolCalls", failedToolCalls),
                Map.entry("elapsedMs", elapsedMs),
                Map.entry("lastToolName", lastToolName),
                Map.entry("lastToolOperation", lastToolOperation),
                Map.entry("lastErrorCode", lastErrorCode),
                Map.entry("lastErrorMessage", lastErrorMessage),
                Map.entry("nextRequiredAction", nextRequiredAction),
                Map.entry("remainingGoalCriteria", remainingGoalCriteria),
                Map.entry("changesMade", changesMade),
                Map.entry("verificationPerformed", verificationPerformed)
        );
    }
}
