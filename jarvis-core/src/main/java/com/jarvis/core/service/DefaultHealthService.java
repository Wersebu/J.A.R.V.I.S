package com.jarvis.core.service;

import com.jarvis.api.service.HealthService;
import com.jarvis.common.dto.HealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default health service for Jarvis version 0.1.
 */
@Service
public class DefaultHealthService implements HealthService {

    private final String version;

    /**
     * Creates the default health service.
     *
     * @param version public Jarvis backend version
     */
    public DefaultHealthService(@Value("${jarvis.version}") String version) {
        this.version = version;
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
