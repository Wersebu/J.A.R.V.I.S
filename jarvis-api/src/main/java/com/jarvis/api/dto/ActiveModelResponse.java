package com.jarvis.api.dto;

/**
 * Response for {@code POST /api/models/active}.
 *
 * @param success whether the switch was applied
 * @param activeModel active model after the request
 * @param error rejection reason, when {@code success} is false
 */
public record ActiveModelResponse(boolean success, String activeModel, String error) {
}
