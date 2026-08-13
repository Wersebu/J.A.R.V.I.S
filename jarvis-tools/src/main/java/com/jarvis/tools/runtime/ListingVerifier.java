package com.jarvis.tools.runtime;

/**
 * Decides whether one read marketplace listing page genuinely matches a search target.
 * Implementations must be general across product categories — no product-specific rules.
 */
public interface ListingVerifier {

    /**
     * Verifies one listing against the search target this verifier was bound to.
     *
     * @param title listing page title
     * @param content listing page content excerpt
     * @return structured verification decision
     */
    ListingVerificationResult verify(String title, String content);
}
