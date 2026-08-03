package com.jarvis.core.service;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds prompts using Jarvis identity loaded from a markdown file.
 */
@Service
public class DefaultPromptBuilder implements PromptBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPromptBuilder.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Resource identityResource;
    private final Clock clock;

    /**
     * Creates the default prompt builder.
     *
     * @param identityResource identity markdown resource
     */
    public DefaultPromptBuilder(@Value("${jarvis.ai.identity-file}") Resource identityResource) {
        this.identityResource = identityResource;
        this.clock = Clock.systemDefaultZone();
    }

    /**
     * Builds the provider prompt with identity, current date, current time, and user message.
     *
     * @param request user chat request
     * @return composed prompt
     */
    @Override
    public String buildPrompt(ChatRequest request) {
        return """
                AI identity:
                %s

                Current date: %s
                Current time: %s

                User message:
                %s
                """.formatted(loadIdentity(), LocalDate.now(clock), LocalTime.now(clock).format(TIME_FORMATTER), request.message());
    }

    private String loadIdentity() {
        try {
            return identityResource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Failed to load AI identity from {}", identityResource, exception);
            return "";
        }
    }
}
