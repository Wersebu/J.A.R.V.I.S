package com.jarvis.brain;

import com.jarvis.common.ai.BrainType;

import java.util.Set;

/**
 * Routes greetings and thanks to the fast brain.
 */
final class GreetingRoutingRule implements BrainRoutingRule {

    private static final Set<String> GREETINGS = Set.of(
            "hello",
            "hi",
            "hey",
            "thanks",
            "thank you",
            "czesc",
            "cześć",
            "dzieki",
            "dzięki"
    );

    /**
     * Checks whether a message is a greeting or thanks.
     *
     * @param normalizedMessage normalized user message
     * @return true when the message is a greeting or thanks
     */
    @Override
    public boolean matches(String normalizedMessage) {
        return GREETINGS.contains(normalizedMessage);
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
        return "Greeting";
    }
}
