package com.jarvis.memory.research;

/**
 * Tool action requested by the research model.
 *
 * @param action strict action name
 * @param tool legacy tool name
 * @param tool tool name
 * @param query search query
 * @param folder folder path
 * @param documentId document UUID, relative path, or graph node id
 * @param phrase phrase to find
 * @param section section heading
 * @param start section start offset
 * @param maxCharacters maximum characters to read
 * @param limit result limit
 * @param answer proposed final answer
 * @param usedDocumentIds supporting documents
 * @param reason reason for the action
 */
public record ResearchAction(
        String action,
        String tool,
        String query,
        String folder,
        String documentId,
        String phrase,
        String section,
        Integer start,
        Integer maxCharacters,
        Integer limit,
        String answer,
        java.util.List<String> usedDocumentIds,
        String reason
) {

    /**
     * Creates a normalized action.
     *
     * @param type action type
     * @param query query text
     * @param documentId document id
     * @return research action
     */
    public static ResearchAction of(ResearchActionType type, String query, String documentId) {
        return new ResearchAction(type.name(), "", query, "", documentId, query, "", 0, 10_000, 10, "", java.util.List.of(), "deterministic fallback");
    }
}
