package com.jarvis.knowledge.vault.read;

import java.util.List;

/**
 * Workflow returned by FIND_WORKFLOW. It carries no procedure text: the model must explicitly
 * select one and read it completely with READ_WORKFLOW.
 *
 * @param documentId stable id
 * @param path vault-relative path
 * @param title title
 * @param status status
 * @param version declared version
 * @param updated declared update date
 * @param estimatedTokens estimated tokens of the whole document
 * @param estimatedParts estimated READ_WORKFLOW parts
 * @param score ranking signal (not a probability)
 * @param matchedSections headings of fragments that matched the query
 */
public record WorkflowCandidate(
        String documentId,
        String path,
        String title,
        String status,
        String version,
        String updated,
        int estimatedTokens,
        int estimatedParts,
        double score,
        List<String> matchedSections
) {
}
