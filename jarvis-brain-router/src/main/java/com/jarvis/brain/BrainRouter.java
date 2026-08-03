package com.jarvis.brain;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatRequest;

/**
 * Selects a logical AI brain for a chat request.
 */
public interface BrainRouter {

    /**
     * Selects the best logical brain for the request.
     *
     * @param request chat request
     * @return selected brain
     */
    Brain select(ChatRequest request);
}
