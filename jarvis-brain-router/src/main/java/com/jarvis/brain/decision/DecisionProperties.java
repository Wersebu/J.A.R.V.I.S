package com.jarvis.brain.decision;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable thresholds for decision making.
 *
 * @param reasoningComplexityThreshold minimum complexity for reasoning brain
 * @param largePromptTokenThreshold estimated prompt tokens treated as large
 * @param knowledgeDocumentEstimate default knowledge document estimate
 * @param knowledgeContextCharactersPerDocument estimated characters per document
 */
@ConfigurationProperties(prefix = "jarvis.decision")
public record DecisionProperties(
        int reasoningComplexityThreshold,
        int largePromptTokenThreshold,
        int knowledgeDocumentEstimate,
        int knowledgeContextCharactersPerDocument
) {

    /**
     * Creates decision properties with conservative defaults.
     */
    public DecisionProperties {
        reasoningComplexityThreshold = reasoningComplexityThreshold <= 0 ? 6 : reasoningComplexityThreshold;
        largePromptTokenThreshold = largePromptTokenThreshold <= 0 ? 800 : largePromptTokenThreshold;
        knowledgeDocumentEstimate = knowledgeDocumentEstimate <= 0 ? 4 : knowledgeDocumentEstimate;
        knowledgeContextCharactersPerDocument = knowledgeContextCharactersPerDocument <= 0
                ? 1_500
                : knowledgeContextCharactersPerDocument;
    }
}
