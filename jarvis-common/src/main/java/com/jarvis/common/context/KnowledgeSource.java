package com.jarvis.common.context;

import java.util.UUID;

/**
 * Source document used in a knowledge context.
 *
 * @param documentId source document identifier
 * @param title source title
 * @param relativePath source path relative to the knowledge root
 * @param category source category
 * @param charactersUsed number of characters used from this source
 */
public record KnowledgeSource(
        UUID documentId,
        String title,
        String relativePath,
        String category,
        long charactersUsed
) {
}
