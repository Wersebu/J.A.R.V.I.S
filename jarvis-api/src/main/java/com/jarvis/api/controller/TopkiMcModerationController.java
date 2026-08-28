package com.jarvis.api.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.jarvis.api.dto.moderation.ModerationHealthResponse;
import com.jarvis.api.dto.moderation.ModerationRequest;
import com.jarvis.api.dto.moderation.ModerationResult;
import com.jarvis.api.service.moderation.TopkiMcModerationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Stateless TopkiMC server-profile moderation endpoint.
 */
@RestController
public class TopkiMcModerationController {

    private final TopkiMcModerationService moderationService;
    private final ObjectReader strictRequestReader;

    public TopkiMcModerationController(TopkiMcModerationService moderationService, ObjectMapper objectMapper) {
        this.moderationService = moderationService;
        this.strictRequestReader = objectMapper.readerFor(ModerationRequest.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping(
            path = "/v1/moderate",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ModerationResult moderate(@RequestBody String body, HttpServletRequest servletRequest) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 64_000) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request body is too large");
        }
        try {
            String keyId = String.valueOf(servletRequest.getAttribute("topkimc.moderation.keyId"));
            return moderationService.moderate(strictRequestReader.readValue(body), UUID.randomUUID().toString(), keyId);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid moderation request");
        }
    }

    @GetMapping(path = "/v1/moderate/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public ModerationHealthResponse health() {
        return moderationService.health();
    }

    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public void unsupportedMediaType() {
    }
}
