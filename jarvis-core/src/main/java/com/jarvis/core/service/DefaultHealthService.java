package com.jarvis.core.service;

import com.jarvis.api.service.HealthService;
import com.jarvis.common.dto.HealthResponse;
import org.springframework.stereotype.Service;

/**
 * Default health service for Jarvis version 0.1.
 */
@Service
public class DefaultHealthService implements HealthService {

    private final String version;

    /**
     * Creates the default health service.
     */
    public DefaultHealthService() {
        this.version = "0.1";
    }

    /**
     * Returns the online status for the backend.
     *
     * @return health response
     */
    @Override
    public HealthResponse health() {
        return new HealthResponse("online", version);
    }
}
