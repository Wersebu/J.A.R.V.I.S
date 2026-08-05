package com.jarvis.tools.runtime;

/**
 * Detects whether a request should enable native tool calling.
 */
public interface ToolIntentDetector {

    /**
     * Detects a tool intent.
     *
     * @param message user message
     * @return detected intent
     */
    ToolIntent detect(String message);
}
