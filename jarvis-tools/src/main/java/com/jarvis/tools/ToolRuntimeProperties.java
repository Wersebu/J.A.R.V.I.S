package com.jarvis.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for native tool-calling runtime budgets.
 *
 * @param enabled whether native tool calling is enabled
 * @param maxCallsFast max tool calls in FAST mode
 * @param maxCallsResearch max tool calls in RESEARCH mode
 * @param maxConsecutiveFailures max consecutive failures before aborting
 * @param timeoutSeconds loop timeout
 */
@ConfigurationProperties(prefix = "jarvis.tools")
public record ToolRuntimeProperties(
        Boolean enabled,
        int maxCallsFast,
        int maxCallsResearch,
        int maxConsecutiveFailures,
        int timeoutSeconds
) {

    /**
     * Applies safe defaults.
     */
    public ToolRuntimeProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        maxCallsFast = maxCallsFast > 0 ? maxCallsFast : 2;
        maxCallsResearch = maxCallsResearch > 0 ? maxCallsResearch : 8;
        maxConsecutiveFailures = maxConsecutiveFailures > 0 ? maxConsecutiveFailures : 2;
        timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 180;
    }

    /**
     * Returns whether native tool calling is enabled.
     *
     * @return true when enabled
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
