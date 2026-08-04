package com.jarvis.common.ai;

import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;

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
}
