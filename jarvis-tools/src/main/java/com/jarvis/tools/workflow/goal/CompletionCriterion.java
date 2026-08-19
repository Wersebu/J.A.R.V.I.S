package com.jarvis.tools.workflow.goal;

/**
 * One condition that must hold for the original request to be considered genuinely satisfied.
 * Declared by the model itself when a {@link GoalContract} is created - never a hardcoded, domain
 * specific rule Core invents on the model's behalf.
 *
 * @param id short stable identifier the model can reference again (e.g. in a later {@code
 *         satisfiedCriteria}/{@code missingCriteria} list), unique within one contract
 * @param description human-readable statement of what must be true
 * @param satisfied whether this criterion is currently believed satisfied, given evidence gathered
 *         so far this loop
 */
public record CompletionCriterion(String id, String description, boolean satisfied) {

    /**
     * Normalizes null fields.
     */
    public CompletionCriterion {
        id = id == null ? "" : id;
        description = description == null ? "" : description;
    }
}
