package com.jarvis.tools.workflow.goal;

/**
 * One piece of evidence a tool call contributed towards the original goal - a compact record kept
 * in the {@link GoalContract}, not the tool call's full raw result (which already lives in the tool
 * loop's own message history and does not need duplicating here).
 *
 * @param sourceTool tool name that produced this evidence
 * @param sourceOperation operation name that produced this evidence
 * @param summary short, model-authored summary of what this evidence actually established
 */
public record AcquiredEvidence(String sourceTool, String sourceOperation, String summary) {

    /**
     * Normalizes null fields.
     */
    public AcquiredEvidence {
        sourceTool = sourceTool == null ? "" : sourceTool;
        sourceOperation = sourceOperation == null ? "" : sourceOperation;
        summary = summary == null ? "" : summary;
    }
}
