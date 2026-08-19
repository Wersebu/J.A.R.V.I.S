package com.jarvis.tools.workflow.goal;

/**
 * Verifies a proposed final answer against its {@link GoalContract} before the tool loop is allowed
 * to accept it - the planned replacement (or, more likely, additional gate ahead) for phrase-based
 * detection in {@code GenericGoalCompletionValidator}.
 *
 * <p>The intended real implementation is a short additional call to whichever {@code AIProvider}/
 * model the loop is already using for this request - never a second, separately-loaded model. On
 * hardware sharing one GPU across the whole active model (this project targets a single consumer
 * GPU), planner/executor/verifier turns must stay sequential calls to the one currently-loaded
 * model, exactly like the existing fallback-text-turn call {@code
 * NativeToolLoopService#fallbackTextTurn} already does - never a second model kept resident
 * alongside the main one.</p>
 *
 * <p>Must stay model-agnostic: it may use native structured/JSON output when the active model and
 * Ollama support it, but must also work from a plain text turn parsed defensively when they don't -
 * never assuming a thinking channel is present, since a provider/model may not expose one at all.</p>
 */
public interface GoalCompletionVerifier {

    /**
     * Verifies whether {@code proposedFinalAnswer} genuinely satisfies {@code contract}.
     *
     * @param contract the goal contract for the current tool-loop execution
     * @param proposedFinalAnswer the model's proposed final answer text
     * @return the verification outcome
     */
    CompletionVerification verify(GoalContract contract, String proposedFinalAnswer);
}
