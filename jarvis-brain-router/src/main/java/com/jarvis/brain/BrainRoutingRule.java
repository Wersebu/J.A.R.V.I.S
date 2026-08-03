package com.jarvis.brain;

import com.jarvis.common.ai.BrainType;

/**
 * Isolated deterministic routing rule.
 */
interface BrainRoutingRule {

    /**
     * Checks whether this rule applies.
     *
     * @param normalizedMessage normalized user message
     * @return true when the rule applies
     */
    boolean matches(String normalizedMessage);

    /**
     * Returns the selected brain type.
     *
     * @return brain type
     */
    BrainType brainType();

    /**
     * Returns a concise selection reason.
     *
     * @return selection reason
     */
    String reason();
}
