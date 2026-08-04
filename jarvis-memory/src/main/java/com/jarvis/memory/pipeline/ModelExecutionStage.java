package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Executes the selected model through the selected provider.
 */
@Service
@Order(90)
public class ModelExecutionStage implements PipelineStage {

    private final List<AIProvider> aiProviders;

    /**
     * Creates the model execution stage.
     *
     * @param aiProviders available providers
     */
    public ModelExecutionStage(List<AIProvider> aiProviders) {
        this.aiProviders = List.copyOf(aiProviders);
    }

    @Override
    public String name() {
        return "Model Execution";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        StringBuilder responseBuilder = new StringBuilder();
        GenerationFinishedHolder finishedHolder = new GenerationFinishedHolder();
        selectProvider(context).stream(context.conversationId(), context.brain(), context.prompt(), event -> {
            if (event instanceof TokenEvent tokenEvent) {
                responseBuilder.append(tokenEvent.text());
            }
            if (event instanceof GenerationFinishedEvent finishedEvent) {
                finishedHolder.event = finishedEvent;
            }
            context.modelEventSink().publish(event);
        });
        return context.withResponse(responseBuilder.toString(), finishedHolder.event);
    }

    private AIProvider selectProvider(PipelineContext context) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(context.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + context.brain().provider()));
    }

    private static final class GenerationFinishedHolder {
        private GenerationFinishedEvent event;
    }
}
