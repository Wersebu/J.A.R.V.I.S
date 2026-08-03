package com.jarvis.brain;

import com.jarvis.common.ai.BrainType;

/**
 * Routes simple short questions to the fast brain.
 */
final class SimpleQuestionRoutingRule implements BrainRoutingRule {

    /**
     * Checks whether a message is a simple question.
     *
     * @param normalizedMessage normalized user message
     * @return true when the message is simple
     */
    @Override
    public boolean matches(String normalizedMessage) {
        return normalizedMessage.length() <= 80
                && (normalizedMessage.startsWith("what time is it")
                || normalizedMessage.startsWith("what is ")
                || normalizedMessage.startsWith("how are you")
                || normalizedMessage.startsWith("jaka jest godzina")
                || normalizedMessage.startsWith("co to jest "));
    }

    /**
     * Returns the selected brain type.
     *
     * @return fast brain type
     */
    @Override
    public BrainType brainType() {
        return BrainType.FAST;
    }

    /**
     * Returns the routing reason.
     *
     * @return routing reason
     */
    @Override
    public String reason() {
        return "Simple question";
    }
}
