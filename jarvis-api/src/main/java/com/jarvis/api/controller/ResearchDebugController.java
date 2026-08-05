package com.jarvis.api.controller;

import com.jarvis.memory.research.ResearchContext;
import com.jarvis.memory.research.ResearchDebugService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Debug endpoint for recent agentic research requests.
 */
@RestController
@RequestMapping(path = "/api/v1/research", produces = MediaType.APPLICATION_JSON_VALUE)
public class ResearchDebugController {

    private final ResearchDebugService debugService;

    /**
     * Creates the controller.
     *
     * @param debugService research debug service
     */
    public ResearchDebugController(ResearchDebugService debugService) {
        this.debugService = debugService;
    }

    /**
     * Returns a recent research request.
     *
     * @param requestId request id
     * @return debug payload
     */
    @GetMapping("/requests/{requestId}")
    public Map<String, Object> request(@PathVariable String requestId) {
        ResearchContext context = debugService.find(requestId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Research request not found"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", context.requestId());
        payload.put("conversationId", context.conversationId());
        payload.put("query", context.originalQuery());
        payload.put("mode", context.mode());
        payload.put("reasoningLevel", context.reasoningLevel().name());
        payload.put("state", context.currentState().name());
        payload.put("stepNumber", context.stepNumber());
        payload.put("searchCount", context.searchCount());
        payload.put("documentReadCount", context.documentReadCount());
        payload.put("totalCharactersRead", context.totalCharactersRead());
        payload.put("actions", context.actions());
        payload.put("observations", previews(context.observations()));
        payload.put("candidateDocumentIds", context.candidateDocumentIds());
        payload.put("readDocumentIds", context.readDocumentIds());
        payload.put("usedDocumentIds", context.usedDocumentIds());
        payload.put("budgets", Map.of(
                        "maxSteps", 8,
                        "maxSearches", 3,
                        "maxDocumentsRead", 5,
                        "maxCharactersPerDocument", 10_000,
                        "maxTotalCharacters", 30_000,
                        "timeoutSeconds", 180
                ));
        payload.put("finalAnswer", context.finalAnswer());
        payload.put("errors", context.errors());
        return payload;
    }

    private List<String> previews(List<String> observations) {
        return observations.stream()
                .map(value -> value.length() <= 900 ? value : value.substring(0, 900) + "...[truncated]")
                .toList();
    }
}
