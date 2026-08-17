package com.jarvis.tools.workflow;

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
}
