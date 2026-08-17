package com.jarvis.tools.workflow;

/**
 * Result of a {@link WorkflowCompletionValidator} check.
 *
 * @param complete whether the proposed final content genuinely represents a finished task
 * @param reason machine-readable reason code, blank when {@code complete}
 * @param guidance corrective instruction to push back to the model when {@code !complete}, telling
 *         it exactly what remains before the task can be considered done
 */
public record CompletionAssessment(boolean complete, String reason, String guidance) {

    /**
     * Normalizes null fields.
     */
    public CompletionAssessment {
        reason = reason == null ? "" : reason;
        guidance = guidance == null ? "" : guidance;
    }

    /**
     * Returns the neutral "nothing to gate on" result.
     *
     * @return a complete assessment with no reason/guidance
     */
    public static CompletionAssessment ok() {
        return new CompletionAssessment(true, "", "");
    }
}
