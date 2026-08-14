package com.jarvis.api.dto;

/**
 * Request body for {@code POST /api/models/active}.
 *
 * @param model model to activate
 */
public record ActiveModelRequest(String model) {
}
