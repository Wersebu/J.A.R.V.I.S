package com.jarvis.api.dto.moderation;

import java.util.List;

/**
 * Strict TopkiMC request shape for server-profile moderation.
 */
public record ModerationRequest(
        String serverId,
        String ownerIdHash,
        String category,
        String languageHint,
        String title,
        String plainText,
        List<String> externalUrls,
        List<String> imageUrls,
        List<String> youtubeVideoIds,
        TechnicalCheckSummary technicalCheckSummary,
        String policyVersion
) {

    public ModerationRequest {
        externalUrls = externalUrls == null ? List.of() : List.copyOf(externalUrls);
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        youtubeVideoIds = youtubeVideoIds == null ? List.of() : List.copyOf(youtubeVideoIds);
    }
}
