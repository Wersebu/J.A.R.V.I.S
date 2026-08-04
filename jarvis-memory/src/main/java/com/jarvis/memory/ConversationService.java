package com.jarvis.memory;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;

import java.util.function.Consumer;

/**
 * Orchestrates conversation state and provider calls.
 */
public interface ConversationService {

    /**
     * Processes a user chat request within a conversation.
     *
     * @param request user chat request
     * @return generated chat response
     */
    ChatResponse chat(ChatRequest request);

    /**
     * Processes a user chat request within a conversation and streams lifecycle events.
     *
     * @param request user chat request
     * @param eventSink event sink
     */
    void stream(ChatRequest request, Consumer<CognitiveEvent> eventSink);
}
