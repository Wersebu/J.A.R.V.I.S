package com.jarvis.api.error;

import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Converts backend exceptions into stable REST error responses.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Handles AI provider failures.
     *
     * @param exception provider exception
     * @return service unavailable response
     */
    @ExceptionHandler(AIProviderException.class)
    public ResponseEntity<ErrorResponse> handleAiProviderException(AIProviderException exception) {
        LOGGER.error("[JARVIS] AI provider error: {}", exception.getMessage(), exception);
        ErrorResponse response = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                "AI provider is unavailable",
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }
}
