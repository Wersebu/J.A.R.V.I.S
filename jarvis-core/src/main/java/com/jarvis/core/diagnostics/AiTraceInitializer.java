package com.jarvis.core.diagnostics;

import com.jarvis.common.trace.AiTraceSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Pushes the configured {@code jarvis.diagnostics.log-full-ai-request}/{@code log-tool-calls}/
 * {@code log-tool-results} flags into {@link AiTraceSettings} once at startup. {@link
 * com.jarvis.common.trace.AiTraceLogger} lives in {@code jarvis-common}, which does not (and should
 * not) depend on Spring configuration binding - this is the one place those two worlds meet.
 */
@Component
@EnableConfigurationProperties(DiagnosticsProperties.class)
public class AiTraceInitializer {

    /**
     * Applies the configured trace flags.
     *
     * @param properties diagnostics properties
     */
    public AiTraceInitializer(DiagnosticsProperties properties) {
        AiTraceSettings.configure(properties.logFullAiRequest(), properties.logToolCalls(), properties.logToolResults());
    }
}
