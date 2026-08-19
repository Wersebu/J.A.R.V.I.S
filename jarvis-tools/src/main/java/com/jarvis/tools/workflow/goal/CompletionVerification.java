package com.jarvis.tools.workflow.goal;

import java.util.List;

/**
 * Result of a dedicated, short completion-verification turn: a genuine model self-assessment of a
 * proposed final answer against its own {@link GoalContract}, not a regex over free text. Designed
 * to be the authoritative signal a future {@code NativeToolLoopService} gate accepts a {@code
 * FINAL_ANSWER} on - {@link CompletionDecision#COMPLETE} only.
 *
 * @param decision the verification outcome
 * @param satisfiedCriteria ids of {@link CompletionCriterion}s the model believes are now satisfied
 * @param missingCriteria ids of {@link CompletionCriterion}s the model believes are still unmet
 * @param nextGoal when {@link CompletionDecision#CONTINUE}, the specific next sub-goal to pursue -
 *         blank when not applicable
 * @param reason short human-readable justification for the decision
 */
public record CompletionVerification(
        CompletionDecision decision,
        List<String> satisfiedCriteria,
        List<String> missingCriteria,
        String nextGoal,
        String reason
) {

    /**
     * Normalizes null fields.
     */
    public CompletionVerification {
        decision = decision == null ? CompletionDecision.CONTINUE : decision;
        satisfiedCriteria = satisfiedCriteria == null ? List.of() : List.copyOf(satisfiedCriteria);
        missingCriteria = missingCriteria == null ? List.of() : List.copyOf(missingCriteria);
        nextGoal = nextGoal == null ? "" : nextGoal;
        reason = reason == null ? "" : reason;
    }
}
