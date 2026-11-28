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
    @Test void llamaContextBudgetIsPerSlotAndReservesReasoningAndAnswer(){
        var selection=new com.jarvis.common.ai.InferenceControl.Selection("config","llamacpp","vision.gguf",java.util.Map.of("contextSize",24576,"parallelSlots",3,"maxOutputTokens",1024));
        var control=org.mockito.Mockito.mock(com.jarvis.common.ai.InferenceControl.class,org.mockito.Mockito.CALLS_REAL_METHODS);
        org.mockito.Mockito.when(control.selection()).thenReturn(selection);
        org.springframework.beans.factory.ObjectProvider<com.jarvis.common.ai.InferenceControl> provider=org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(control);
        var service=new ContextBudgetService(new AiContextProperties(24576,2048,0));service.engines(provider);
        assertThat(service.inputBudget(new com.jarvis.common.ai.Brain(com.jarvis.common.ai.BrainType.FAST,"llamacpp","vision.gguf",""),com.jarvis.common.ai.AIJobType.CHAT)).isEqualTo(6912);
    }
}
