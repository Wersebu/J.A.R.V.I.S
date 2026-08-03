package com.jarvis.common.ai;

import com.jarvis.common.dto.ChatResponse;

/**
 * Provider-independent contract for AI chat generation.
 */
public interface AIProvider {

    /**
     * Returns the provider identifier handled by this implementation.
     *
     * @return provider identifier
     */
    String provider();

    /**
     * Sends a prepared prompt to the selected AI brain.
     *
     * @param brain selected logical brain
     * @param prompt prepared prompt
     * @return provider chat response
     */
    ChatResponse chat(Brain brain, String prompt);
}
