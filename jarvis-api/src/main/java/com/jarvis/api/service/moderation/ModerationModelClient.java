package com.jarvis.api.service.moderation;

import com.jarvis.api.dto.moderation.ModerationRequest;

import java.time.Duration;

/**
 * Low-level moderation-only model client.
 */
public interface ModerationModelClient {

    /**
     * Calls the configured model with moderation-only messages.
     *
     * @param request untrusted moderation payload
     * @param systemPrompt trusted moderation prompt
     * @param model configured model name
     * @param timeout remaining timeout budget
     * @return raw model JSON content and latency
     */
    ModerationModelResponse moderate(ModerationRequest request, String systemPrompt, String model, Duration timeout);

    /**
     * Checks whether Ollama is reachable and the named model is installed.
     *
     * @param model configured model name
     * @param timeout timeout
     * @return model availability
     */
    ModerationModelAvailability availability(String model, Duration timeout);
}
