package com.jarvis.tools.runtime;

import com.jarvis.common.ai.ModelMessage;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.workflow.goal.GoalContract;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Core-owned mutable state for one native agent execution.
 */
final class AgentExecutionState {

    private GoalContract goalContract;
    private final List<ModelMessage> messages;
    private final List<ToolRuntimeStep> steps;
    private final List<ToolResult> results;
    private final List<String> errors;
    private final Map<String, Object> acquiredFacts = new LinkedHashMap<>();
    private final ConnectedRuntimeState runtimeState = new ConnectedRuntimeState();
    private final Set<String> callFingerprints = new LinkedHashSet<>();
    private final Map<String, Integer> operationRepeatCounts = new LinkedHashMap<>();
    private final Map<String, Integer> recoveryAttempts = new LinkedHashMap<>();
    private int completionAttempts;
    private int goalCompletionAttempts;
    private int completionRecoveryExtensionsUsed;

    AgentExecutionState(
            GoalContract goalContract,
            List<ModelMessage> messages,
            List<ToolRuntimeStep> steps,
            List<ToolResult> results,
            List<String> errors
    ) {
        this.goalContract = goalContract;
        this.messages = messages;
        this.steps = steps;
        this.results = results;
        this.errors = errors;
    }

    GoalContract goalContract() {
        return goalContract;
    }

    void goalContract(GoalContract goalContract) {
        this.goalContract = goalContract;
    }

    List<ModelMessage> messages() {
        return messages;
    }

    List<ToolRuntimeStep> steps() {
        return steps;
    }

    List<ToolResult> results() {
        return results;
    }

    List<String> errors() {
        return errors;
    }

    Map<String, Object> acquiredFacts() {
        return acquiredFacts;
    }

    ConnectedRuntimeState runtimeState() {
        return runtimeState;
    }

    Set<String> callFingerprints() {
        return callFingerprints;
    }

    Map<String, Integer> operationRepeatCounts() {
        return operationRepeatCounts;
    }

    Map<String, Integer> recoveryAttempts() {
        return recoveryAttempts;
    }

    int completionAttempts() {
        return completionAttempts;
    }

    int incrementCompletionAttempts() {
        return ++completionAttempts;
    }

    int goalCompletionAttempts() {
        return goalCompletionAttempts;
    }

    int incrementGoalCompletionAttempts() {
        return ++goalCompletionAttempts;
    }

    int completionRecoveryExtensionsUsed() {
        return completionRecoveryExtensionsUsed;
    }

    void completionRecoveryExtensionsUsed(int completionRecoveryExtensionsUsed) {
        this.completionRecoveryExtensionsUsed = completionRecoveryExtensionsUsed;
    }
}
