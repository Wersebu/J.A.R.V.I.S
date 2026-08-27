package com.jarvis.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration for native tool-calling runtime budgets.
 *
 * @param enabled whether native tool calling is enabled
 * @param maxCallsFast max tool calls in FAST mode
 * @param maxCallsResearch max tool calls in RESEARCH mode
 * @param maxConsecutiveFailures max consecutive failures before aborting
 * @param timeoutSeconds loop timeout
 * @param runtime default runtime: native or legacy
 * @param maxConsecutiveOperationRepeats max consecutive calls to the same tool+operation
 *        (regardless of arguments) before the loop refuses further calls to it - a coarser,
 *        argument-agnostic no-progress guard on top of the exact-fingerprint duplicate blocker
 * @param statefulWorkflowMinToolBudget minimum tool-call budget once a stateful workflow (e.g.
 *        Store Audit) is genuinely engaged - detected from real loop state (an existing dataset for
 *        the conversation, or a storeDataset operation actually executing this loop), never from a
 *        keyword classifier alone. A full Store Audit pass (start/append/finalize/verify/read
 *        workflow doc/geocode/retry/route/optimize/submit/retry/final answer) can easily need more
 *        turns than a plain web-search floor
 * @param maxConsecutiveNoToolProgressTurns hard backstop across every "model returned text with zero
 *        native tool calls, re-enter the loop" path (a text-shaped TOOL_REQUEST re-entry, the live
 *        evidence gate, the workflow/goal completion gate, ...) - once this many CONSECUTIVE turns in
 *        a row produced neither a tool call nor any other real progress, the loop stops immediately
 *        with {@link com.jarvis.tools.runtime.ToolLoopTerminationReason#NO_NATIVE_TOOL_CALL_PROGRESS}
 *        instead of bouncing the same corrective system message at the model until the turn/timeout
 *        budget runs out. Reset the instant any turn actually calls a tool (successful or not - a
 *        real attempt is real engagement, unlike repeating plain text)
 * @param maxLiveEvidenceRecoveryAttempts bounded retry budget specifically for the "final answer
 *        requires live evidence, but the loop has collected none yet" recovery nudge - mirrors the
 *        existing malformed-continuation/completion-gate retry budgets so this one specific recovery
 *        reason can never loop unboundedly on its own even before the general {@code
 *        maxConsecutiveNoToolProgressTurns} backstop above would catch it
 */
@ConfigurationProperties(prefix = "jarvis.tools")
public record ToolRuntimeProperties(
        Boolean enabled,
        int maxCallsFast,
        int maxCallsResearch,
        int maxConsecutiveFailures,
        int timeoutSeconds,
        String runtime,
        int maxConsecutiveOperationRepeats,
        int statefulWorkflowMinToolBudget,
        int maxConsecutiveNoToolProgressTurns,
        int maxLiveEvidenceRecoveryAttempts
) {

    /**
     * Applies safe defaults.
     */
    @ConstructorBinding
    public ToolRuntimeProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxCallsFast = maxCallsFast > 0 ? maxCallsFast : 8;
        maxCallsResearch = maxCallsResearch > 0 ? maxCallsResearch : 15;
        maxConsecutiveFailures = maxConsecutiveFailures > 0 ? maxConsecutiveFailures : 2;
        timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 600;
        runtime = runtime == null || runtime.isBlank() ? "native" : runtime.trim().toLowerCase(java.util.Locale.ROOT);
        maxConsecutiveOperationRepeats = maxConsecutiveOperationRepeats > 0 ? maxConsecutiveOperationRepeats : 5;
        statefulWorkflowMinToolBudget = statefulWorkflowMinToolBudget > 0 ? statefulWorkflowMinToolBudget : 20;
        maxConsecutiveNoToolProgressTurns = maxConsecutiveNoToolProgressTurns > 0 ? maxConsecutiveNoToolProgressTurns : 2;
        maxLiveEvidenceRecoveryAttempts = maxLiveEvidenceRecoveryAttempts > 0 ? maxLiveEvidenceRecoveryAttempts : 3;
    }

    /**
     * Backward-compatible constructor used by older tests/call sites built before {@code
     * maxConsecutiveNoToolProgressTurns}/{@code maxLiveEvidenceRecoveryAttempts} existed.
     */
    public ToolRuntimeProperties(
            Boolean enabled,
            int maxCallsFast,
            int maxCallsResearch,
            int maxConsecutiveFailures,
            int timeoutSeconds,
            String runtime,
            int maxConsecutiveOperationRepeats,
            int statefulWorkflowMinToolBudget
    ) {
        this(enabled, maxCallsFast, maxCallsResearch, maxConsecutiveFailures, timeoutSeconds, runtime,
                maxConsecutiveOperationRepeats, statefulWorkflowMinToolBudget, 2, 3);
    }

    /**
     * Backward-compatible constructor used by older tests/call sites built before {@code
     * statefulWorkflowMinToolBudget} existed.
     */
    public ToolRuntimeProperties(
            Boolean enabled,
            int maxCallsFast,
            int maxCallsResearch,
            int maxConsecutiveFailures,
            int timeoutSeconds,
            String runtime,
            int maxConsecutiveOperationRepeats
    ) {
        this(enabled, maxCallsFast, maxCallsResearch, maxConsecutiveFailures, timeoutSeconds, runtime, maxConsecutiveOperationRepeats, 20);
    }

    /**
     * Backward-compatible constructor used by older tests/call sites built before
     * {@code maxConsecutiveOperationRepeats} existed.
     */
    public ToolRuntimeProperties(
            Boolean enabled,
            int maxCallsFast,
            int maxCallsResearch,
            int maxConsecutiveFailures,
            int timeoutSeconds,
            String runtime
    ) {
        this(enabled, maxCallsFast, maxCallsResearch, maxConsecutiveFailures, timeoutSeconds, runtime, 5, 20);
    }

    /**
     * Backward-compatible constructor used by older tests.
     */
    public ToolRuntimeProperties(
            Boolean enabled,
            int maxCallsFast,
            int maxCallsResearch,
            int maxConsecutiveFailures,
            int timeoutSeconds
    ) {
        this(enabled, maxCallsFast, maxCallsResearch, maxConsecutiveFailures, timeoutSeconds, "legacy", 5, 20);
    }

    /**
     * Returns whether native tool calling is enabled.
     *
     * @return true when enabled
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * Returns true when the native runtime should be used.
     *
     * @return true for native runtime
     */
    public boolean isNativeRuntime() {
        return "native".equalsIgnoreCase(runtime);
    }
}
