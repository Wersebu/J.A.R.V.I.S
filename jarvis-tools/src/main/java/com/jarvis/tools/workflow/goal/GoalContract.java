package com.jarvis.tools.workflow.goal;

import java.util.List;

/**
 * Explicit decomposition of what the current request actually requires. It is created once before
 * the first real tool call and carried through the rest of the native tool loop as real Core-held
 * state (not re-derived from scratch each turn), so the model can never lose sight of the original
 * goal behind a narrow intermediate tool result.
 *
 * <p><b>Runtime status: wired into {@code NativeToolLoopService}.</b> The loop stores the active
 * contract in {@code AgentExecutionState}, appends {@link AcquiredEvidence} after tool results and
 * runtime recovery actions, and accepts a proposed final answer only after a structured {@link
 * CompletionVerification} returns {@link CompletionDecision#COMPLETE}. A {@code CONTINUE} decision
 * re-enters the same native tool loop with the full tool catalog still available.</p>
 *
 * <p>Deliberately generic - nothing here or in any caller may hardcode a specific domain (Roblox,
 * Store Audit, or otherwise). {@code completionCriteria} and {@code requiredOutcome} are text for
 * the specific request at hand, never a fixed catalog Core selects from.</p>
 *
 * @param originalGoal the user's original request, verbatim or a faithful restatement
 * @param requiredOutcome what a genuinely complete answer must actually contain/accomplish
 * @param completionCriteria the conditions that together define "done" for this specific request
 * @param acquiredEvidence evidence gathered so far, most recent last
 * @param remainingRequirements what is still missing, in the model's own words - empty when nothing
 *         is believed to be missing
 * @param canFinalize the model's own running belief about whether it could finalize right now -
 *         advisory only; the authoritative decision is {@link CompletionVerification#decision()}
 *         from a dedicated verification turn, never this field taken at face value
 */
public record GoalContract(
        String originalGoal,
        String requiredOutcome,
        List<CompletionCriterion> completionCriteria,
        List<AcquiredEvidence> acquiredEvidence,
        List<String> remainingRequirements,
        boolean canFinalize
) {

    /**
     * Normalizes null fields.
     */
    public GoalContract {
        originalGoal = originalGoal == null ? "" : originalGoal;
        requiredOutcome = requiredOutcome == null ? "" : requiredOutcome;
        completionCriteria = completionCriteria == null ? List.of() : List.copyOf(completionCriteria);
        acquiredEvidence = acquiredEvidence == null ? List.of() : List.copyOf(acquiredEvidence);
        remainingRequirements = remainingRequirements == null ? List.of() : List.copyOf(remainingRequirements);
    }

    /**
     * Returns a copy with one more piece of evidence appended - the contract's other fields (goal,
     * criteria definitions) never change once created; only evidence accumulates and criteria
     * satisfaction/remaining-requirements get re-assessed as the loop progresses.
     *
     * @param evidence evidence to append
     * @return updated contract
     */
    public GoalContract withEvidence(AcquiredEvidence evidence) {
        List<AcquiredEvidence> updated = new java.util.ArrayList<>(acquiredEvidence);
        updated.add(evidence);
        return new GoalContract(originalGoal, requiredOutcome, completionCriteria, updated, remainingRequirements, canFinalize);
    }
}
