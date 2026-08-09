package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Parses and validates the main model structured action envelope.
 */
@Service
public class MainModelActionParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /**
     * Creates the parser.
     *
     * @param objectMapper JSON mapper
     */
    public MainModelActionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a raw model response into a validated action.
     *
     * @param raw raw model output
     * @return action
     */
    public MainModelAction parse(String raw) {
        try {
            JsonNode node = objectMapper.readTree(extractJson(raw));
            MainModelActionType type = MainModelActionType.valueOf(text(node, "type"));
            return switch (type) {
                case FINAL_ANSWER -> finalAnswer(firstText(node, "answer", "response", "content", "message"));
                case TOOL_REQUEST -> toolRequest(text(node, "goal"), text(node, "reason"), context(node));
                case CLARIFICATION -> clarification(firstText(node, "question", "answer", "message", "content"));
            };
        } catch (IllegalArgumentException | JsonProcessingException exception) {
            throw new MainModelActionParsingException("Invalid main model action JSON", exception);
        }
    }

    /**
     * Creates a safe direct final answer action.
     *
     * @param answer answer text
     * @return final answer action
     */
    public MainModelAction finalAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            throw new MainModelActionParsingException("FINAL_ANSWER requires answer");
        }
        return new MainModelAction(MainModelActionType.FINAL_ANSWER, answer.strip(), "", "", "", Map.of());
    }

    private MainModelAction toolRequest(String goal, String reason, Map<String, Object> context) {
        if (goal == null || goal.isBlank()) {
            throw new MainModelActionParsingException("TOOL_REQUEST requires goal");
        }
        return new MainModelAction(MainModelActionType.TOOL_REQUEST, "", goal.strip(), safe(reason), "", context);
    }

    private MainModelAction clarification(String question) {
        if (question == null || question.isBlank()) {
            throw new MainModelActionParsingException("CLARIFICATION requires question");
        }
        return new MainModelAction(MainModelActionType.CLARIFICATION, "", "", "", question.strip(), Map.of());
    }

    private Map<String, Object> context(JsonNode node) {
        JsonNode context = node.path("context");
        if (!context.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(context, MAP_TYPE);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").strip();
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String extractJson(String raw) {
        String value = raw == null ? "" : raw.strip();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new MainModelActionParsingException("No JSON object found");
        }
        return value.substring(start, end + 1);
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
