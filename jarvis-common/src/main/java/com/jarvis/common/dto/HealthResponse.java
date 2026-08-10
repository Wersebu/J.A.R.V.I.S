package com.jarvis.common.dto;

/**
 * Health response for public service status checks.
 *
 * @param status current service status
 * @param version public backend version
 * @param modelStatus startup model warmup status
 */
public record HealthResponse(String status, String version, String modelStatus) {

    /**
     * Creates a health response without model readiness details.
     *
     * @param status current service status
     * @param version public backend version
     */
    public HealthResponse(String status, String version) {
        this(status, version, "UNKNOWN");
    }
}
