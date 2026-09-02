package com.jarvis.ollama;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enforces model prompt budgets before requests are sent to Ollama.
 */
@Service
public class ContextBudgetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextBudgetService.class);
    private static final int CHARS_PER_TOKEN_ESTIMATE = 4;

    private final AiContextProperties properties;

    /**
     * Creates the budget service.
     *
     * @param properties AI context properties
     */
    public ContextBudgetService(AiContextProperties properties) {
        this.properties = properties;
    }

    /**
     * Returns request options for Ollama.
     *
     * @return JSON-friendly options
     */
    public java.util.Map<String, Object> ollamaOptions() {
        return java.util.Map.of(
                "num_ctx", properties.contextWindow(),
                "repeat_penalty", properties.repeatPenalty()
        );
    }

    /**
     * Applies the configured input budget to a prompt.
     *
     * @param model model name
     * @param prompt original prompt
     * @return prompt within budget
     */
    public String fitPrompt(String model, String prompt) {
        String value = prompt == null ? "" : prompt;
        int estimatedTokens = estimateTokens(value);
        int maxInputTokens = properties.maxInputTokens();
        if (estimatedTokens <= maxInputTokens) {
            LOGGER.info("[CONTEXT_BUDGET] model={} contextWindow={} reservedOutputTokens={} estimatedInputTokens={} status=OK",
                    model, properties.contextWindow(), properties.reservedOutputTokens(), estimatedTokens);
            return value;
        }
        int maxChars = Math.max(1_000, maxInputTokens * CHARS_PER_TOKEN_ESTIMATE);
        int headChars = Math.min(value.length(), maxChars / 3);
        int tailChars = Math.min(value.length() - headChars, maxChars - headChars - 220);
        String compacted = value.substring(0, headChars)
                + "\n\n[CONTEXT_BUDGET] Middle prompt content omitted to preserve system/tool contract and latest request.\n\n"
                + value.substring(Math.max(headChars, value.length() - tailChars));
        LOGGER.warn("[CONTEXT_BUDGET] model={} contextWindow={} reservedOutputTokens={} estimatedInputTokens={} maxInputTokens={} status=COMPACTED compactedEstimatedTokens={}",
                model, properties.contextWindow(), properties.reservedOutputTokens(), estimatedTokens, maxInputTokens,
                estimateTokens(compacted));
        return compacted;
    }

    /**
     * Estimates tokens from characters.
     *
     * @param value text
     * @return rough token count
     */
    public int estimateTokens(String value) {
        return (int) Math.ceil((value == null ? 0 : value.length()) / (double) CHARS_PER_TOKEN_ESTIMATE);
    }
}
