package com.jarvis.core.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for request diagnostics.
 *
 * @param enabled whether diagnostics are enabled
 * @param requestHistorySize in-memory diagnostics history size
 * @param logPromptPreview whether prompt previews may be logged
 * @param exposeDebugApi whether diagnostics endpoints are enabled
 */
@ConfigurationProperties(prefix = "jarvis.diagnostics")
public record DiagnosticsProperties(
        boolean enabled,
        int requestHistorySize,
        boolean logPromptPreview,
        boolean exposeDebugApi
) {

    /**
     * Creates properties with safe defaults.
     */
    public DiagnosticsProperties {
        if (requestHistorySize <= 0) {
            requestHistorySize = 100;
        }
    }
}
