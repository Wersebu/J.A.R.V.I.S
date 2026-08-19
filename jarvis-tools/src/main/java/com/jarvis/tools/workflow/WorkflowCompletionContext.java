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
 * @param requiredDocumentLoaded whether a {@code knowledge__read_document} call matching {@link
 *         WorkflowCompletionValidator#requiredDocumentPath()} succeeded during this tool loop -
 *         always {@code true} when the active validator declares no required document
 * @param datasetCreationFailed whether a {@code storeDataset.CREATE_DATASET}/{@code START_DATASET}
 *         call executed during this loop but was rejected (e.g. invalid provenance, a bad
 *         attachment index) - distinct from {@code activeDatasetId} being blank for a completely
 *         unrelated reason (no dataset operation attempted at all), so a rejected creation attempt
 *         can never be mistaken for "workflow not engaged"
 * @param lastDatasetCreationErrorMessage the rejected creation call's own error message, so the
 *         validator can hand the model the exact reason instead of a generic "try again"
 * @param originalUserRequest the user's original message for this turn, so a generic validator can
 *         hand it back in corrective guidance without the loop needing a workflow-specific copy
 * @param toolCallCount number of tool calls that actually executed (not blocked/invalid) so far
 *         this loop, regardless of which tool - zero means nothing has been gated on yet
 * @param bootstrapOnlyEvidence true when every successful tool call this loop classified as {@link
 *         ToolOperationRole#isBootstrap()} (discovery/selection only) - false the moment any call
 *         with a different role succeeds, and always false when {@code toolCallCount} is zero
 * @param proposedFinalText the model's own proposed final content plus reasoning/thinking text for
 *         this turn, so a validator can detect the model admitting its own answer is incomplete
 */
public record WorkflowCompletionContext(
        String requestId,
        String conversationId,
        boolean datasetTouchedThisLoop,
        String activeDatasetId,
        boolean requiredDocumentLoaded,
        boolean datasetCreationFailed,
        String lastDatasetCreationErrorMessage,
        String originalUserRequest,
        int toolCallCount,
        boolean bootstrapOnlyEvidence,
        String proposedFinalText
) {

    /**
     * Normalizes null fields.
     */
    public WorkflowCompletionContext {
        requestId = requestId == null ? "" : requestId;
        conversationId = conversationId == null ? "" : conversationId;
        activeDatasetId = activeDatasetId == null ? "" : activeDatasetId;
        lastDatasetCreationErrorMessage = lastDatasetCreationErrorMessage == null ? "" : lastDatasetCreationErrorMessage;
        originalUserRequest = originalUserRequest == null ? "" : originalUserRequest;
        proposedFinalText = proposedFinalText == null ? "" : proposedFinalText;
    }

    /**
     * Backward-compatible constructor for callers built before the generic goal-completion fields
     * existed.
     *
     * @param requestId pipeline request id
     * @param conversationId owning conversation id
     * @param datasetTouchedThisLoop whether a dataset-touching operation executed during this loop
     * @param activeDatasetId the most recently touched dataset id in this loop, blank if none
     * @param requiredDocumentLoaded whether the required workflow document was read this loop
     * @param datasetCreationFailed whether a dataset creation call was rejected this loop
     * @param lastDatasetCreationErrorMessage the rejected creation call's own error message
     */
    public WorkflowCompletionContext(
            String requestId,
            String conversationId,
            boolean datasetTouchedThisLoop,
            String activeDatasetId,
            boolean requiredDocumentLoaded,
            boolean datasetCreationFailed,
            String lastDatasetCreationErrorMessage
    ) {
        this(requestId, conversationId, datasetTouchedThisLoop, activeDatasetId, requiredDocumentLoaded,
                datasetCreationFailed, lastDatasetCreationErrorMessage, "", 0, false, "");
    }

    /**
     * Backward-compatible constructor for callers with no dataset-creation-failure tracking.
     *
     * @param requestId pipeline request id
     * @param conversationId owning conversation id
     * @param datasetTouchedThisLoop whether a dataset-touching operation executed during this loop
     * @param activeDatasetId the most recently touched dataset id in this loop, blank if none
     * @param requiredDocumentLoaded whether the required workflow document was read this loop
     */
    public WorkflowCompletionContext(
            String requestId,
            String conversationId,
            boolean datasetTouchedThisLoop,
            String activeDatasetId,
            boolean requiredDocumentLoaded
    ) {
        this(requestId, conversationId, datasetTouchedThisLoop, activeDatasetId, requiredDocumentLoaded, false, "",
                "", 0, false, "");
    }
}
