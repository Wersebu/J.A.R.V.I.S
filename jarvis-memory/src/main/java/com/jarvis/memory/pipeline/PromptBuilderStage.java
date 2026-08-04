package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.prompt.PromptBuilder;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Builds the provider prompt.
 */
@Service
@Order(80)
public class PromptBuilderStage implements PipelineStage {

    private final PromptBuilder promptBuilder;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the prompt builder stage.
     *
     * @param promptBuilder prompt builder
     * @param cognitiveEventBus event bus
     */
    public PromptBuilderStage(PromptBuilder promptBuilder, CognitiveEventBus cognitiveEventBus) {
        this.promptBuilder = promptBuilder;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "PromptBuilderStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_STARTED, "BUILDING", "Building prompt", null, Map.of(
                "documentsUsed", context.knowledgeContext().sourceCount()
        ));
        Instant startedAt = Instant.now();
        String prompt = promptBuilder.buildPrompt(context.request(), context.knowledgeContext());
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_FINISHED, "FINISHED", "Prompt built", null, Map.of(
                "promptBuildTimeMs", durationMs,
                "promptCharacters", prompt.length(),
                "estimatedPromptTokens", prompt.length() / 4
        ));
        return context.withPrompt(prompt)
                .withMetadata("promptBuildTimeMs", durationMs)
                .withMetadata("estimatedPromptTokens", prompt.length() / 4);
    }
}
