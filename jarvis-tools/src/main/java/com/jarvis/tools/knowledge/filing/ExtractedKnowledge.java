package com.jarvis.tools.knowledge.filing;

import java.util.List;

/**
 * Structured knowledge extracted from a user instruction.
 */
public record ExtractedKnowledge(
        String subject,
        String relation,
        String value,
        String normalizedFact,
        KnowledgeKind kind,
        List<String> entities,
        List<String> tags,
        String language,
        double confidence,
        boolean worthSaving,
        String sourceMessage
) {

    /**
     * Creates an immutable extraction result.
     */
    public ExtractedKnowledge {
        subject = subject == null ? "" : subject;
        relation = relation == null ? "" : relation;
        value = value == null ? "" : value;
        normalizedFact = normalizedFact == null ? "" : normalizedFact;
        kind = kind == null ? KnowledgeKind.OTHER : kind;
        entities = entities == null ? List.of() : List.copyOf(entities);
        tags = tags == null ? List.of() : List.copyOf(tags);
        language = language == null || language.isBlank() ? "pl" : language;
        sourceMessage = sourceMessage == null ? "" : sourceMessage;
    }
}
