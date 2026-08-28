package com.jarvis.api.service;

import java.time.Duration;
import java.util.Map;

/**
 * Core-side port for Coding Agent operations that must execute on the connected Windows UI host.
 */
public interface WindowsCodingBridgeGateway {

    /**
     * Returns a compact availability status for Coding Agent's Windows executor.
     *
     * @return status string
     */
    String codingStatus();

    /**
     * Executes one Coding Agent operation on the connected Windows executor.
     *
     * @param operation operation name
     * @param payload operation payload
     * @param timeout timeout for the round trip
     * @return structured response payload
     */
    Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout);
}
