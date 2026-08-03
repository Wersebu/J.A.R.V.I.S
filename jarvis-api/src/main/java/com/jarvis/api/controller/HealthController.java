package com.jarvis.api.controller;

import com.jarvis.api.service.HealthService;
import com.jarvis.common.dto.HealthResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for service health checks.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final HealthService healthService;

    /**
     * Creates a health controller.
     *
     * @param healthService health service
     */
    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    /**
     * Reports the current service status.
     *
     * @return online status and public version
     */
    @GetMapping("/health")
    public HealthResponse health() {
        return healthService.health();
    }
}
