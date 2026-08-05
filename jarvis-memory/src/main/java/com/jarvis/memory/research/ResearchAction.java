package com.jarvis.memory.research;

/**
 * Tool action requested by the research model.
 *
 * @param tool tool name
 * @param query search query
 * @param folder folder path
 * @param documentId document UUID, relative path, or graph node id
 * @param phrase phrase to find
 * @param section section heading
 * @param reason reason for the action
 */
public record ResearchAction(
        String tool,
        String query,
        String folder,
        String documentId,
        String phrase,
        String section,
        String reason
) {
}
