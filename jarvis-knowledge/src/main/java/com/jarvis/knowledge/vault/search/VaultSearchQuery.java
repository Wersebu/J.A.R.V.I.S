package com.jarvis.knowledge.vault.search;

import java.util.List;
import java.util.Locale;

/**
 * Vault search request.
 *
 * @param text query text
 * @param mode {@link Mode#TEXT} (keywords only) or {@link Mode#HYBRID} (keywords + embeddings)
 * @param types allowed document types (empty = any)
 * @param project required project (blank = any)
 * @param tags tags that must all be present
 * @param statuses allowed statuses (empty = any active)
 * @param pathPrefix required folder prefix (blank = whole vault)
 * @param includeArchived include archived / inactive documents
 * @param workflowsOnly restrict to workflow documents
 * @param limit maximum fragments (0 = configured default)
 * @param maxContextTokens token budget for returned text (0 = configured default)
 */
public record VaultSearchQuery(
        String text,
        Mode mode,
        List<String> types,
        String project,
        List<String> tags,
        List<String> statuses,
        String pathPrefix,
        boolean includeArchived,
        boolean workflowsOnly,
        int limit,
        int maxContextTokens
) {

    /**
     * Normalizes the request.
     */
    public VaultSearchQuery {
        text = text == null ? "" : text.strip();
        mode = mode == null ? Mode.HYBRID : mode;
        types = lower(types);
        project = project == null ? "" : project.strip();
        tags = lower(tags).stream().map(tag -> tag.startsWith("#") ? tag.substring(1) : tag).toList();
        statuses = lower(statuses);
        pathPrefix = pathPrefix == null ? "" : pathPrefix.strip().replace('\\', '/');
    }

    /**
     * Creates a plain hybrid query.
     *
     * @param text query
     * @return query
     */
    public static VaultSearchQuery of(String text) {
        return new VaultSearchQuery(text, Mode.HYBRID, List.of(), "", List.of(), List.of(), "", false, false, 0, 0);
    }

    private static List<String> lower(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .flatMap(value -> List.of(value.split(",")).stream())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    /**
     * Search mode.
     */
    public enum Mode {
        /** Keyword (BM25) search only. */
        TEXT,
        /** Keyword search fused with embedding similarity; degrades to TEXT when embeddings fail. */
        HYBRID
    }
}
