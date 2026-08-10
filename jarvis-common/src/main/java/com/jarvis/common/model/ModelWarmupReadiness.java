package com.jarvis.common.model;

import java.util.Map;

/**
 * Exposes startup model warmup readiness without coupling callers to a provider.
 */
public interface ModelWarmupReadiness {

    /**
     * Returns the current global model warmup status.
     *
     * @return warmup status
     */
    ModelWarmupStatus status();

    /**
     * Returns true when all eager startup models are warm.
     *
     * @return readiness flag
     */
    default boolean ready() {
        return status() == ModelWarmupStatus.READY;
    }

    /**
     * Returns model-specific startup policies.
     *
     * @return model policy map
     */
    Map<String, ModelStartupPolicy> policies();

    /**
     * Returns model-specific warmup statuses.
     *
     * @return model status map
     */
    Map<String, ModelWarmupStatus> modelStatuses();
}
