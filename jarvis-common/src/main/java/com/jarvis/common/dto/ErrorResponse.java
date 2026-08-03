package com.jarvis.common.dto;

import java.time.Instant;

/**
 * Error response returned by the REST API.
 *
 * @param status HTTP status code
 * @param error error name
 * @param message safe error message
 * @param timestamp response timestamp
 */
public record ErrorResponse(int status, String error, String message, Instant timestamp) {
}
