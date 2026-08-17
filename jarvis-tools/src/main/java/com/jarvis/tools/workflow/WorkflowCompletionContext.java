package com.jarvis.tools.workflow;

/**
 * State a {@link WorkflowCompletionValidator} needs to decide whether an active tool-loop turn's
 * proposed final content actually represents a finished task, or just a status update mid-workflow.
 *
 * @param requestId pipeline request id
 * @param conversationId owning conversation id
 * @param datasetTouchedThisLoop whether a {@code storeDataset} or {@code location.GEOCODE_DATASET}
 *         operation actually executed during this tool loop - a workflow is only gated on a
 *         dataset the model is actively engaged with right now, never merely because one happens
 *         to exist for the conversation (e.g. an unrelated later turn must not be blocked by it)
 * @param activeDatasetId the most recently touched dataset id in this loop, blank if none
 */
public record WorkflowCompletionContext(
        String requestId,
        String conversationId,
        boolean datasetTouchedThisLoop,
        String activeDatasetId
) {

    /**
     * Normalizes null fields.
     */
    public WorkflowCompletionContext {
        requestId = requestId == null ? "" : requestId;
        conversationId = conversationId == null ? "" : conversationId;
        activeDatasetId = activeDatasetId == null ? "" : activeDatasetId;
    }
}
