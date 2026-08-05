package com.jarvis.common.prompt;

/**
 * Source available to ground a model response.
 *
 * @param type source type
 * @param id source identifier
 * @param title source title
 * @param contentPreview short source content preview
 * @param confidence source confidence
 */
public record GroundingSource(
        GroundingSourceType type,
        String id,
        String title,
        String contentPreview,
        double confidence
) {
}
