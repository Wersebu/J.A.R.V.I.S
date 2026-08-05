package com.jarvis.memory.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Locale;

/**
 * Parses and repairs strict research actions returned by the model.
 */
@Service
public class ResearchActionParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(ResearchActionParser.class);

    private final ObjectMapper objectMapper;

    /**
     * Creates the parser.
     *
     * @param objectMapper JSON mapper
     */
    public ResearchActionParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses a model action, performs one repair attempt, then falls back from state.
     *
     * @param raw raw model output
     * @param provider provider used for repair
     * @param repairPrompt repair prompt
     * @param context research context
     * @return parsed action
     */
    public ResearchAction parseOrFallback(String raw, AIProvider provider, Brain brain, String repairPrompt, ResearchContext context) {
        try {
            return validate(parse(raw));
        } catch (ResearchActionParseException first) {
                context.addError("INVALID_ACTION_JSON: " + first.getMessage());
                LOGGER.warn("[JARVIS][RESEARCH][requestId={}] INVALID_ACTION_JSON {}", context.requestId(), first.getMessage());
            try {
                LOGGER.info("[JARVIS][RESEARCH][requestId={}] REPAIR_ATTEMPT", context.requestId());
                ChatResponse repaired = provider.chat(brain, repairPrompt, AIJobType.BACKGROUND);
                ResearchAction action = validate(parse(repaired.response()));
                LOGGER.info("[JARVIS][RESEARCH][requestId={}] REPAIR_RESULT success", context.requestId());
                return action;
            } catch (RuntimeException repairFailure) {
                context.addError("REPAIR_FAILED: " + repairFailure.getMessage());
                ResearchAction fallback = fallback(context);
                LOGGER.info("[JARVIS][RESEARCH][requestId={}] AUTOMATIC_FALLBACK_ACTION {}", context.requestId(), fallback.action());
                return fallback;
            }
        }
    }

    private ResearchAction parse(String raw) {
        try {
            return objectMapper.readValue(extractJson(raw), ResearchAction.class);
        } catch (IOException exception) {
            throw new ResearchActionParseException("Invalid JSON", exception);
        }
    }

    private ResearchAction validate(ResearchAction action) {
        ResearchActionType type = type(action);
        return switch (type) {
            case SEARCH_KNOWLEDGE -> requireText(action.query(), action, "query");
            case READ_DOCUMENT -> requireText(action.documentId(), action, "documentId");
            case FIND_IN_DOCUMENT -> requireText(action.documentId(), requireAny(action.query(), action.phrase(), action, "query"), "documentId");
            case READ_SECTION -> requireText(action.documentId(), action, "documentId");
            case LIST_KNOWLEDGE, FINAL_ANSWER -> action;
        };
    }

    /**
     * Returns the normalized action type.
     *
     * @param action action
     * @return action type
     */
    public ResearchActionType type(ResearchAction action) {
        String value = action.action();
        if (value == null || value.isBlank()) {
            value = legacy(action.tool());
        }
        try {
            return ResearchActionType.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ResearchActionParseException("Unsupported action: " + value, exception);
        }
    }

    private ResearchAction fallback(ResearchContext context) {
        if (context.hasCandidates() && !context.hasReadContent()) {
            return ResearchAction.of(ResearchActionType.READ_DOCUMENT, context.originalQuery(), nodeId(context.bestCandidate()));
        }
        if (context.hasReadContent()) {
            return new ResearchAction(ResearchActionType.FINAL_ANSWER.name(), "", "", "", "", "", "", 0, 0, 0, "", java.util.List.copyOf(context.usedDocumentIds()), "content is available");
        }
        return ResearchAction.of(ResearchActionType.SEARCH_KNOWLEDGE, context.originalQuery(), "");
    }

    private ResearchAction requireText(String value, ResearchAction action, String field) {
        if (value == null || value.isBlank()) {
            throw new ResearchActionParseException("Missing field: " + field);
        }
        return action;
    }

    private ResearchAction requireAny(String first, String second, ResearchAction action, String field) {
        if ((first == null || first.isBlank()) && (second == null || second.isBlank())) {
            throw new ResearchActionParseException("Missing field: " + field);
        }
        return action;
    }

    private String legacy(String tool) {
        String normalized = tool == null ? "" : tool.toLowerCase(Locale.ROOT).replace("_", "").strip();
        return switch (normalized) {
            case "knowledge.search", "search" -> ResearchActionType.SEARCH_KNOWLEDGE.name();
            case "knowledge.list", "list" -> ResearchActionType.LIST_KNOWLEDGE.name();
            case "knowledge.read", "read" -> ResearchActionType.READ_DOCUMENT.name();
            case "knowledge.find", "find" -> ResearchActionType.FIND_IN_DOCUMENT.name();
            case "knowledge.readsection", "readsection" -> ResearchActionType.READ_SECTION.name();
            case "knowledge.finish", "finish" -> ResearchActionType.FINAL_ANSWER.name();
            default -> tool == null ? "" : tool;
        };
    }

    private String extractJson(String value) {
        if (value == null) {
            return "{}";
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        return start >= 0 && end > start ? value.substring(start, end + 1) : value;
    }

    private String nodeId(com.jarvis.knowledge.KnowledgeDocument document) {
        return document == null ? "" : "knowledge-document:" + document.relativePath().replace('\\', '/');
    }

}
