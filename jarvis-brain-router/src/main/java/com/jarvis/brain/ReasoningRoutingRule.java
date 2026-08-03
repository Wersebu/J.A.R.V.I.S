package com.jarvis.brain;

import com.jarvis.common.ai.BrainType;

import java.util.List;

/**
 * Routes complex, programming, and architecture requests to the reasoning brain.
 */
final class ReasoningRoutingRule implements BrainRoutingRule {

    private static final int LONG_MESSAGE_THRESHOLD = 300;
    private static final List<String> REASONING_KEYWORDS = List.of(
            "spring",
            "java",
            "architecture",
            "architectural",
            "planning",
            "plugin development",
            "large explanation",
            "explain",
            "implement",
            "refactor",
            "maven",
            "backend",
            "projekt",
            "architektura",
            "zaimplementuj",
            "napraw"
    );

    /**
     * Checks whether the request needs deeper reasoning.
     *
     * @param normalizedMessage normalized user message
     * @return true when deeper reasoning is useful
     */
    @Override
    public boolean matches(String normalizedMessage) {
        return normalizedMessage.length() >= LONG_MESSAGE_THRESHOLD
                || REASONING_KEYWORDS.stream().anyMatch(normalizedMessage::contains);
    }

    /**
     * Returns the selected brain type.
     *
     * @return reasoning brain type
     */
    @Override
    public BrainType brainType() {
        return BrainType.REASONING;
    }

    /**
     * Returns the routing reason.
     *
     * @return routing reason
     */
    @Override
    public String reason() {
        return "Complex request";
    }
}
