package com.jarvis.common.event;

import com.jarvis.common.ai.BrainType;

import java.time.Instant;

/**
 * Event emitted when generation completes.
 *
 * @param type event type
 * @param timestamp event timestamp
 * @param conversationId conversation identifier
 * @param generationTimeMs total generation time in milliseconds
 * @param brain selected logical brain
 * @param model selected model
 * @param promptTokens prompt token count, when available
 * @param completionTokens completion token count, when available
 * @param tokensPerSecond generated tokens per second, when available
 */
public record GenerationFinishedEvent(
        ChatEventType type,
        Instant timestamp,
        String conversationId,
        long generationTimeMs,
        BrainType brain,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Double tokensPerSecond
) implements ChatEvent {

    /**
     * Creates a generation-finished event.
     *
     * @param conversationId conversation identifier
     * @param generationTimeMs total generation time in milliseconds
     * @param brain selected logical brain
     * @param model selected model
     * @param promptTokens prompt token count, when available
     * @param completionTokens completion token count, when available
     * @param tokensPerSecond generated tokens per second, when available
     * @return event
     */
    public static GenerationFinishedEvent create(
            String conversationId,
            long generationTimeMs,
            BrainType brain,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Double tokensPerSecond
    ) {
        return new GenerationFinishedEvent(
                ChatEventType.FINISHED,
                Instant.now(),
                conversationId,
                generationTimeMs,
                brain,
                model,
                promptTokens,
                completionTokens,
                tokensPerSecond
        );
    }
}
