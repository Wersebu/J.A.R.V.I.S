package com.jarvis.knowledge.vault.search;

import java.util.List;

/**
 * Result of a vault search.
 *
 * @param query query text
 * @param mode requested mode
 * @param effectiveMode mode actually used ({@code TEXT} after an embedding fallback)
 * @param hits returned fragments
 * @param omitted fragments that matched but did not fit the context budget (sources only, no text)
 * @param candidates number of fragments that matched before limits
 * @param semanticUsed whether embedding similarity contributed
 * @param semanticError why embeddings were not used, or {@code ""}
 * @param contextTokens tokens of returned fragment text
 * @param elapsedMs search time
 * @param noResults true when nothing relevant was found
 * @param notes guidance for the reader (model or UI)
 */
public record VaultSearchResult(
        String query,
        String mode,
        String effectiveMode,
        List<VaultSearchHit> hits,
        List<Omitted> omitted,
        int candidates,
        boolean semanticUsed,
        String semanticError,
        int contextTokens,
        long elapsedMs,
        boolean noResults,
        List<String> notes
) {

    /**
     * Fragment left out because of the context budget.
     *
     * @param path path
     * @param title title
     * @param headingPath headings
     * @param startLine first line
     * @param endLine last line
     * @param tokenCount fragment tokens
     */
    public record Omitted(String path, String title, List<String> headingPath, int startLine, int endLine, int tokenCount) {
    }
}
