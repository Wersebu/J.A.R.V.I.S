package com.jarvis.common.dto;

/**
 * Health response for public service status checks.
 *
 * @param status current service status
 * @param version public backend version
 */
public record HealthResponse(String status, String version) {
}
