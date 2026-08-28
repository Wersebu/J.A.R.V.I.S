package com.jarvis.core.moderation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;

/**
 * Safe diagnostic wrapper for moderation model parsing failures.
 */
class ModerationParsingException extends RuntimeException {

    private final String stage;
    private final String pointer;
    private final String expectedType;
    private final String actualType;
    private final int responseLength;

    ModerationParsingException(String stage, String pointer, String expectedType, String actualType,
            int responseLength, Throwable cause) {
        super("Moderation parse failed at " + stage, cause);
        this.stage = stage;
        this.pointer = pointer;
        this.expectedType = expectedType;
        this.actualType = actualType;
        this.responseLength = responseLength;
    }

    String stage() {
        return stage;
    }

    String pointer() {
        return pointer;
    }

    String expectedType() {
        return expectedType;
    }

    String actualType() {
        return actualType;
    }

    int responseLength() {
        return responseLength;
    }

    String jacksonExceptionName() {
        return getCause() == null ? getClass().getSimpleName() : getCause().getClass().getSimpleName();
    }

    static ModerationParsingException fromJackson(String stage, int responseLength, Throwable cause) {
        String pointer = "/";
        String expected = "unknown";
        String actual = "unknown";
        if (cause instanceof JsonMappingException mappingException && !mappingException.getPath().isEmpty()) {
            StringBuilder builder = new StringBuilder();
            for (JsonMappingException.Reference reference : mappingException.getPath()) {
                if (reference.getFieldName() != null) {
                    builder.append('/').append(reference.getFieldName());
                } else {
                    builder.append('/').append(reference.getIndex());
                }
            }
            pointer = builder.toString();
        }
        if (cause instanceof MismatchedInputException mismatchedInputException) {
            Class<?> targetType = mismatchedInputException.getTargetType();
            if (targetType != null) {
                expected = targetType.getTypeName();
            }
            JsonParser parser = mismatchedInputException.getProcessor() instanceof JsonParser jsonParser ? jsonParser : null;
            JsonToken token = parser == null ? null : parser.currentToken();
            if (token != null) {
                actual = token.name();
            }
        }
        return new ModerationParsingException(stage, pointer, expected, actual, responseLength, cause);
    }
}
