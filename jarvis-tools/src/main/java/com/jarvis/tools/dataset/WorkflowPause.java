package com.jarvis.tools.dataset;

/**
 * Whether a {@link StoreAuditDataset} is legitimately paused waiting on a real decision from the
 * user, as opposed to a workflow that simply stalled - so {@link
 * com.jarvis.tools.workflow.StoreAuditWorkflowCompletionValidator} can tell the two apart instead
 * of treating every non-terminal stop as an incomplete task.
 */
public enum WorkflowPause {
    /** Not paused - proceeding normally through the pipeline. */
    NONE,
    /** Paused to ask the user about scheduling preferences (days, distribution). */
    AWAITING_PREFERENCES,
    /** Paused to ask the user about a borderline planning decision (e.g. a daily-limit tradeoff). */
    AWAITING_DECISION
}
