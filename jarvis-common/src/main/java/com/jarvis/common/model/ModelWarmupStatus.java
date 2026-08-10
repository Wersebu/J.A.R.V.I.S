package com.jarvis.common.model;

/**
 * Runtime state of startup model warmup.
 */
public enum ModelWarmupStatus {
    /**
     * Startup warmup has not begun yet.
     */
    NOT_STARTED,

    /**
     * Startup warmup is currently running.
     */
    WARMING,

    /**
     * All eager startup models are warm.
     */
    READY,

    /**
     * At least one eager startup model failed to warm.
     */
    FAILED
}
