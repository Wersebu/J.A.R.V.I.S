package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.prompt.PromptBuilder;
import com.jarvis.common.prompt.PromptContext;
import com.jarvis.common.prompt.PromptContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptBuilderStage.class);

    private final PromptBuilder promptBuilder;
    private final PromptContextFactory promptContextFactory;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the prompt builder stage.
     *
     * @param promptBuilder prompt builder
     * @param promptContextFactory prompt context factory
     * @param cognitiveEventBus event bus
     */
    public PromptBuilderStage(
            PromptBuilder promptBuilder,
            PromptContextFactory promptContextFactory,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.promptBuilder = promptBuilder;
        this.promptContextFactory = promptContextFactory;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "PromptBuilderStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_STARTED, "BUILDING", "Building prompt", null, Map.of(
                "documentsUsed", context.knowledgeContext().sourceCount(),
                "conversationMessages", context.conversation().size()
        ));
        Instant startedAt = Instant.now();
        PromptContext promptContext = promptContextFactory.create(
                context.request().message(),
                context.memoryContext(),
                context.knowledgeContext(),
                context.conversation()
        );
        String prompt = promptBuilder.buildPrompt(
                context.request(),
                context.knowledgeContext(),
                context.memoryContext(),
                promptContext
        );
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        LOGGER.info("[JARVIS] Prompt built characters={} estimatedTokens={} conversationMessages={} documents={}",
                prompt.length(), prompt.length() / 4, context.conversation().size(), context.knowledgeContext().sourceCount());
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_FINISHED, "FINISHED", "Prompt built", null, Map.of(
                "promptBuildTimeMs", durationMs,
                "promptCharacters", prompt.length(),
                "estimatedPromptTokens", prompt.length() / 4
        ));
        return context.withPromptContext(promptContext)
                .withPrompt(prompt)
                .withMetadata("promptBuildTimeMs", durationMs)
                .withMetadata("estimatedPromptTokens", prompt.length() / 4)
                .withMetadata("responseMode", promptContext.responseMode().name())
                .withMetadata("personalQuery", promptContext.personalQueryAnalysis().personalQuery());
    }

}
