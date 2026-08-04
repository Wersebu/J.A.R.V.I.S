package com.jarvis.api.service;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;

import java.util.function.Consumer;

/**
 * Coordinates a client message with the backend AI provider.
 */
public interface ChatService {

    /**
     * Processes a chat request and returns plain text output.
     *
     * @param request chat request
     * @return generated chat response
     */
    ChatResponse chat(ChatRequest request);

    /**
     * Processes a chat request and streams lifecycle events.
     *
     * @param request chat request
     * @param eventSink event sink
     */
    void stream(ChatRequest request, Consumer<CognitiveEvent> eventSink);
}
