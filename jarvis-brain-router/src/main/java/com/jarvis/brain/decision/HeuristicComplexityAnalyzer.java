package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Scores request complexity using deterministic heuristics.
 */
@Service
public class HeuristicComplexityAnalyzer implements ComplexityAnalyzer {

    /**
     * Calculates request complexity.
     *
     * @param request chat request
     * @param taskAnalysis task analysis
     * @param knowledgeAnalysis knowledge analysis
     * @return complexity score
     */
    @Override
    public ComplexityScore analyze(ChatRequest request, TaskAnalysis taskAnalysis, KnowledgeAnalysis knowledgeAnalysis) {
        String query = request.message() == null ? "" : request.message();
        String normalized = query.toLowerCase(Locale.ROOT);
        int score = 1;
        String reason = "Low complexity";

        score += Math.min(3, query.length() / 180);
        score += switch (taskAnalysis.taskType()) {
            case PROGRAMMING, CODE_REVIEW, ANALYSIS -> 4;
            case CONTENT_GENERATION -> 4;
            case SUMMARIZATION, KNOWLEDGE_QA -> 2;
            case CREATIVE_WRITING, TRANSLATION -> 1;
            default -> 0;
        };
        if (knowledgeAnalysis != null && knowledgeAnalysis.required()) {
            score += 1;
        }
        if (contains(normalized, "five", "5", "multiple", "several", "architecture", "step by step", "detailed")) {
            score += 2;
        }
        if (contains(normalized, "analyze", "reason", "compare", "tradeoff", "debug")) {
            score += 2;
        }
        int attachmentCount = request.attachments() == null ? 0 : request.attachments().size();
        if (attachmentCount > 0) {
            // Attachments (photos, documents, screenshots of lists) very often carry
            // extraction/multi-step tool work a text-length-only heuristic never sees - e.g. reading
            // a dozen rows off two photos and turning them into a dataset, geocoding, and scheduling.
            // Scale with count but stay capped, so a single casual attachment question is never
            // automatically treated as maximally complex.
            score += Math.min(4, attachmentCount + 1);
        }

        score = Math.max(1, Math.min(10, score));
        if (score >= 7) {
            reason = "High complexity";
        } else if (score >= 4) {
            reason = "Medium complexity";
        }
        return new ComplexityScore(score, reason);
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
