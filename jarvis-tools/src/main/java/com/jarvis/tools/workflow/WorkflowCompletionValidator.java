package com.jarvis.tools.workflow;

import java.util.Optional;

/**
 * Pluggable check the native tool loop consults before accepting a model's proposed final content
 * as a genuinely finished task, instead of a status update mid-workflow.
 *
 * <p>The agent loop itself ({@code NativeToolLoopService}) stays generic: it only knows how to ask
 * "is this workflow actually done?" and re-enter the loop with corrective guidance when the answer
 * is no. A specific stateful workflow (e.g. Store Audit scheduling) provides its own implementation
 * that knows what "done" means for it - the loop never hardcodes workflow-specific logic.</p>
 */
public interface WorkflowCompletionValidator {

    /**
     * Assesses whether the active workflow, if any, is actually complete.
     *
     * @param context state describing what this tool loop touched
     * @return assessment; {@link CompletionAssessment#ok()} when there is nothing to gate on
     */
    CompletionAssessment assess(WorkflowCompletionContext context);

    /**
     * The logical Knowledge Workspace path of a document this workflow requires to have been read
     * (via {@code knowledge__read_document}) before it can be considered progressing, if any. The
     * loop stays generic - it never hardcodes any specific document path - it only tracks whether a
     * successful {@code READ_DOCUMENT} call this turn matched the path a validator declares here,
     * and reports that back via {@link WorkflowCompletionContext#requiredDocumentLoaded()}.
     *
     * @return the required document's logical path, or empty when this workflow needs none
     */
    default Optional<String> requiredDocumentPath() {
        return Optional.empty();
    }
}
