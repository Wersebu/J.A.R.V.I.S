package com.jarvis.api.dto.moderation;

import java.util.List;

/**
 * Strict TopkiMC moderation response schema.
 */
public record ModerationResult(
        ModerationDecision decision,
        ModerationRisk risk,
        List<ModerationCategory> categories,
        String reasonCode,
        String summary,
        boolean adminReviewRequired,
        String modelVersion,
        String policyVersion
) {

    public ModerationResult {
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    public static ModerationResult error(String reasonCode, String modelVersion, String policyVersion) {
        return new ModerationResult(
                ModerationDecision.ERROR,
                ModerationRisk.HIGH,
                List.of(),
                reasonCode,
                "Audyt nie jest wiarygodny; wymagana kontrola administratora.",
                true,
                modelVersion == null || modelVersion.isBlank() ? "n/a" : modelVersion,
                policyVersion == null || policyVersion.isBlank() ? "v1" : policyVersion
        );
    }
}
