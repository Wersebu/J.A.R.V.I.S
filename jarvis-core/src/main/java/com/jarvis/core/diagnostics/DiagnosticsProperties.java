package com.jarvis.core.diagnostics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for request diagnostics.
 *
 * @param enabled whether diagnostics are enabled
 * @param requestHistorySize in-memory diagnostics history size
 * @param logPromptPreview whether prompt previews may be logged - a separate, pre-existing feature,
 *         never tied to the full AI/tool trace flags below
 * @param exposeDebugApi whether diagnostics endpoints are enabled
 * @param logFullAiRequest whether the exact outbound AI request JSON (post context-budgeting, the
 *         real payload sent to Ollama) may be logged - see {@code AiTraceLogger}
 * @param logToolCalls whether model tool calls, tool-execution-begin, and MCP-call-begin events may
 *         be logged
 * @param logToolResults whether tool/MCP results may be logged
 */
@ConfigurationProperties(prefix = "jarvis.diagnostics")
public record DiagnosticsProperties(
        boolean enabled,
        int requestHistorySize,
        boolean logPromptPreview,
        boolean exposeDebugApi,
        boolean logFullAiRequest,
        boolean logToolCalls,
        boolean logToolResults
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
