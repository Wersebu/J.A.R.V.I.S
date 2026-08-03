package com.jarvis.common.dto;

/**
 * Plain text chat response returned by Jarvis.
 *
 * @param conversationId stable conversation identifier
 * @param response response text
 */
public record ChatResponse(String conversationId, String response) {
}
