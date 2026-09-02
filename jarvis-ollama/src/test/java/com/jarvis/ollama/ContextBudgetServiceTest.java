package com.jarvis.ollama;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBudgetServiceTest {

    @Test
    void exposesConfiguredOllamaContextWindow() {
        ContextBudgetService service = new ContextBudgetService(new AiContextProperties(16_384, 2_048, 0));

        assertThat(service.ollamaOptions()).containsEntry("num_ctx", 16_384);
    }

    @Test
    void compactsPromptWhenInputWouldConsumeReservedOutputBudget() {
        ContextBudgetService service = new ContextBudgetService(new AiContextProperties(1_024, 256, 0));
        String prompt = "x".repeat(5_000);

        String compacted = service.fitPrompt("gpt-oss:20b", prompt);

        assertThat(compacted.length()).isLessThan(prompt.length());
        assertThat(compacted).contains("[CONTEXT_BUDGET]");
        assertThat(service.estimateTokens(compacted)).isLessThanOrEqualTo(768);
    }
}
