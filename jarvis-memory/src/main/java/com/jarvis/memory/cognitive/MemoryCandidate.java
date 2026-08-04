package com.jarvis.memory.cognitive;

/**
 * Memory candidate extracted by deterministic rules.
 *
 * @param type candidate type
 * @param subject candidate subject
 * @param predicate candidate predicate or title
 * @param value candidate value
 * @param confidence confidence score
 */
public record MemoryCandidate(
        MemoryCandidateType type,
        String subject,
        String predicate,
        String value,
        double confidence
) {
}
