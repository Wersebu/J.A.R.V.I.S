package com.jarvis.common.trace;

/**
 * Process-wide, cheap-to-check gates for {@link AiTraceLogger}. Populated once at startup from
 * {@code jarvis.diagnostics.log-full-ai-request}/{@code log-tool-calls}/{@code log-tool-results}
 * (a Spring {@code @ConfigurationProperties} bean lives in {@code jarvis-core}, which this module
 * does not depend on - a plain static holder lets every module that needs to check "is tracing on"
 * do so without a new constructor dependency anywhere, and defaults every flag to {@code false} so
 * a module under test that never initializes this holder gets exactly the same behavior as
 * production with tracing disabled).
 *
 * <p>These are three independent flags, deliberately never tied to {@code log-prompt-preview} -
 * that flag is a separate, pre-existing feature with its own (currently unused) purpose.</p>
 */
public final class AiTraceSettings {

    private static volatile boolean logFullAiRequest = false;
    private static volatile boolean logToolCalls = false;
    private static volatile boolean logToolResults = false;

    private AiTraceSettings() {
    }

    /**
     * Applies the configured flags - called once at startup.
     *
     * @param fullAiRequest whether full outbound AI request payloads may be logged
     * @param toolCalls whether model tool calls and tool execution begin events may be logged
     * @param toolResults whether tool/MCP results may be logged
     */
    public static void configure(boolean fullAiRequest, boolean toolCalls, boolean toolResults) {
        logFullAiRequest = fullAiRequest;
        logToolCalls = toolCalls;
        logToolResults = toolResults;
    }

    /**
     * Resets every flag to disabled - for test isolation only.
     */
    public static void reset() {
        configure(false, false, false);
    }

    /**
     * Whether full outbound AI request payloads (the exact JSON sent to Ollama) may be logged.
     *
     * @return true when enabled
     */
    public static boolean logFullAiRequest() {
        return logFullAiRequest;
    }

    /**
     * Whether model tool calls and tool-execution-begin events may be logged.
     *
     * @return true when enabled
     */
    public static boolean logToolCalls() {
        return logToolCalls;
    }

    /**
     * Whether tool/MCP results may be logged.
     *
     * @return true when enabled
     */
    public static boolean logToolResults() {
        return logToolResults;
    }
}
