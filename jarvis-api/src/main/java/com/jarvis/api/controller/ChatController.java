package com.jarvis.api.controller;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for plain text chat interactions.
 */
@RestController
@RequestMapping(path = "/api/v1/chat", produces = MediaType.APPLICATION_JSON_VALUE)
public class ChatController {

    private final ChatService chatService;

    /**
     * Creates a chat controller.
     *
     * @param chatService chat orchestration service
     */
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * Sends a user message to Jarvis and returns the generated response.
     *
     * @param request chat request
     * @return chat response
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@RequestBody ChatRequest request) {
        return chatService.chat(request);
    }
}
