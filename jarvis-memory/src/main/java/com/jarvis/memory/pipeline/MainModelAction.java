package com.jarvis.memory.pipeline;

import java.util.Map;

/**
 * Structured action envelope returned by the main model.
 *
 * @param type action type
 * @param answer final answer text
 * @param goal external-capability goal
 * @param reason decision reason summary
 * @param question clarification question
 * @param context optional structured context
 */
public record MainModelAction(
        MainModelActionType type,
        String answer,
        String goal,
        String reason,
        String question,
        Map<String, Object> context
) {

    /**
     * Creates an immutable action envelope.
     */
    public MainModelAction {
        answer = answer == null ? "" : answer;
        goal = goal == null ? "" : goal;
        reason = reason == null ? "" : reason;
        question = question == null ? "" : question;
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
