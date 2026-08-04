package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Estimates local knowledge usage with deterministic heuristics.
 */
@Service
public class HeuristicKnowledgeRequirementAnalyzer implements KnowledgeRequirementAnalyzer {

    private final DecisionProperties properties;

    /**
     * Creates the analyzer.
     *
     * @param properties decision properties
     */
    public HeuristicKnowledgeRequirementAnalyzer(DecisionProperties properties) {
        this.properties = properties;
    }

    /**
     * Analyzes knowledge requirements.
     *
     * @param request chat request
     * @param taskAnalysis task analysis
     * @return knowledge analysis
     */
    @Override
    public KnowledgeAnalysis analyze(ChatRequest request, TaskAnalysis taskAnalysis) {
        String query = request.message() == null ? "" : request.message().toLowerCase(Locale.ROOT);
        boolean required = taskAnalysis.taskType() == TaskType.KNOWLEDGE_QA
                || contains(query, "document", "file", "knowledge", "project", "spring", "jarvis", "wiedza", "plik");
        int documents = required ? properties.knowledgeDocumentEstimate() : 0;
        int contextSize = documents * properties.knowledgeContextCharactersPerDocument();
        return new KnowledgeAnalysis(
                required,
                documents,
                contextSize,
                required ? "Knowledge question" : "No local knowledge required"
        );
    }

    private boolean contains(String query, String... keywords) {
        for (String keyword : keywords) {
            if (query.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
