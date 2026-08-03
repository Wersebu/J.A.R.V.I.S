package com.jarvis.api.service;

import com.jarvis.common.dto.HealthResponse;

/**
 * Provides service health information.
 */
public interface HealthService {

    /**
     * Returns the current health state.
     *
     * @return health response
     */
    HealthResponse health();
}
