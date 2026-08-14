package com.jarvis.api.error;

import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.dto.ErrorResponse;
import com.jarvis.knowledge.KnowledgeException;
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

    /**
     * Handles knowledge engine failures.
     *
     * @param exception knowledge exception
     * @return internal server error response
     */
    @ExceptionHandler(KnowledgeException.class)
    public ResponseEntity<ErrorResponse> handleKnowledgeException(KnowledgeException exception) {
        LOGGER.error("[JARVIS] Knowledge engine error: {}", exception.getMessage(), exception);
        ErrorResponse response = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "Knowledge engine error",
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }

    /**
     * Handles request validation failures (e.g. an unsupported or oversized attachment upload).
     *
     * <p>The exception message is already a safe, user-facing validation reason (never an internal
     * detail) - see {@code DefaultTemporaryWorkspaceService}, which is the primary source of these
     * exceptions for attachment/image uploads.
     *
     * @param exception validation exception
     * @return bad request response carrying the safe validation message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        LOGGER.info("[JARVIS] Rejected request: {}", exception.getMessage());
        ErrorResponse response = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Bad Request",
                exception.getMessage(),
                Instant.now()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }
}
