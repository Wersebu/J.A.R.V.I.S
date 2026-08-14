package com.jarvis.common.ai;

import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;

import java.util.List;

/**
 * Provider-independent contract for AI chat generation.
 */
public interface AIProvider {

    /**
     * Returns the provider identifier handled by this implementation.
     *
     * @return provider identifier
     */
    String provider();

    /**
     * Sends a prepared prompt to the selected AI brain.
     *
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @return provider chat response
     */
    ChatResponse chat(Brain brain, String prompt);

    /**
     * Sends a prepared prompt to the selected AI brain as a specific job type.
     *
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @param jobType job type
     * @return provider chat response
     */
    default ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
        return chat(brain, prompt);
    }

    /**
     * Streams a prepared prompt through the selected AI brain.
     *
     * @param conversationId conversation identifier
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @param eventSink event sink
     */
    void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink);

    /**
     * Streams a prepared prompt through the selected AI brain as a specific job type.
     *
     * @param conversationId conversation identifier
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @param jobType job type
     * @param eventSink event sink
     */
    default void stream(String conversationId, Brain brain, String prompt, AIJobType jobType, ChatEventSink eventSink) {
        stream(conversationId, brain, prompt, eventSink);
    }

    /**
     * Streams a prepared prompt, with optional images, through the selected AI brain.
     *
     * <p>Providers that do not override this default silently ignore images and behave exactly as
     * before images existed - callers should only pass a non-empty list once the caller has already
     * confirmed the selected model supports vision.
     *
     * @param conversationId conversation identifier
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @param jobType job type
     * @param images images to send alongside the prompt, empty for a text-only request
     * @param eventSink event sink
     */
    default void stream(String conversationId, Brain brain, String prompt, AIJobType jobType, List<ImageAttachment> images, ChatEventSink eventSink) {
        stream(conversationId, brain, prompt, jobType, eventSink);
    }

    /**
     * Executes one non-streaming native tool-calling model turn.
     *
     * @param brain selected logical brain
     * @param messages conversation messages
     * @param tools scoped native tool definitions
     * @param jobType job type
     * @return model response with content, thinking and native tool calls
     */
    default ModelResponse toolChat(
            Brain brain,
            List<ModelMessage> messages,
            List<NativeToolDefinition> tools,
            AIJobType jobType
    ) {
        throw new AIProviderException("Native tool calling is not supported by provider: " + provider());
    }
}
