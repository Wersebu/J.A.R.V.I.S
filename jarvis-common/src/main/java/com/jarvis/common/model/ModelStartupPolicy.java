package com.jarvis.common.model;

/**
 * Startup loading policy for AI models.
 */
public enum ModelStartupPolicy {
    /**
     * Load and keep the model warm during Core startup.
     */
    EAGER,

    /**
     * Load the model only when a feature explicitly needs it.
     */
    LAZY,

    /**
     * Disable the model completely.
     */
    DISABLED
}
