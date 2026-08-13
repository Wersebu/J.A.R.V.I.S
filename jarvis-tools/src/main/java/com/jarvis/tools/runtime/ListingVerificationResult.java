package com.jarvis.tools.runtime;

import java.util.List;

/**
 * Structured decision for whether one concrete marketplace listing genuinely matches the search
 * target the model is looking for.
 *
 * @param accepted true when the listing matches the search target and its required variant
 * @param confidence 0.0-1.0 confidence in the decision
 * @param reason short human-readable explanation
 * @param matchedProduct the product the listing was identified as, when accepted
 * @param matchedVariant the specific variant/capacity/model identified, when accepted
 * @param evidence short excerpts supporting the decision
 */
public record ListingVerificationResult(
        boolean accepted,
        double confidence,
        String reason,
        String matchedProduct,
        String matchedVariant,
        List<String> evidence
) {

    /**
     * Creates an immutable result.
     */
    public ListingVerificationResult {
        reason = reason == null ? "" : reason;
        matchedProduct = matchedProduct == null ? "" : matchedProduct;
        matchedVariant = matchedVariant == null ? "" : matchedVariant;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        confidence = Math.max(0.0d, Math.min(1.0d, confidence));
    }

    /**
     * Creates a rejection result with a diagnostic reason.
     *
     * @param reason why the listing was rejected
     * @return rejection result
     */
    public static ListingVerificationResult reject(String reason) {
        return new ListingVerificationResult(false, 0.0d, reason, "", "", List.of());
    }
}
