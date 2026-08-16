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
 */
@ConfigurationProperties(prefix = "jarvis.tools")
public record ToolRuntimeProperties(
        Boolean enabled,
        int maxCallsFast,
        int maxCallsResearch,
        int maxConsecutiveFailures,
        int timeoutSeconds,
        String runtime,
        int maxConsecutiveOperationRepeats
) {

    /**
     * Applies safe defaults.
     */
    @ConstructorBinding
    public ToolRuntimeProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxCallsFast = maxCallsFast > 0 ? maxCallsFast : 2;
        maxCallsResearch = maxCallsResearch > 0 ? maxCallsResearch : 8;
        maxConsecutiveFailures = maxConsecutiveFailures > 0 ? maxConsecutiveFailures : 2;
        timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 180;
        runtime = runtime == null || runtime.isBlank() ? "native" : runtime.trim().toLowerCase(java.util.Locale.ROOT);
        maxConsecutiveOperationRepeats = maxConsecutiveOperationRepeats > 0 ? maxConsecutiveOperationRepeats : 5;
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
        this(enabled, maxCallsFast, maxCallsResearch, maxConsecutiveFailures, timeoutSeconds, runtime, 5);
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
        this(enabled, maxCallsFast, maxCallsResearch, maxConsecutiveFailures, timeoutSeconds, "legacy", 5);
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
