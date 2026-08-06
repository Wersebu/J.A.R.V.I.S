package com.jarvis.tools.knowledge.filing;

import java.util.List;

/**
 * Canonical filing plan for extracted knowledge.
 */
public record KnowledgeDestinationPlan(
        String operation,
        String targetPath,
        String existingDocumentId,
        String documentTitle,
        String section,
        String reason,
        double confidence,
        List<String> alternatives
) {

    /**
     * Creates an immutable plan.
     */
    public KnowledgeDestinationPlan {
        operation = operation == null ? "SKIP" : operation;
        targetPath = targetPath == null ? "" : targetPath;
        existingDocumentId = existingDocumentId == null ? "" : existingDocumentId;
        documentTitle = documentTitle == null ? "" : documentTitle;
        section = section == null ? "" : section;
        reason = reason == null ? "" : reason;
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }
}
