package com.jarvis.tools.dataset;

/**
 * Verification state of a single {@link StoreRecord}.
 */
public enum VerificationStatus {
    /** Not yet checked by a second verification pass. */
    UNVERIFIED,
    /** Confirmed correct as extracted, by a second verification pass. */
    VERIFIED,
    /** Corrected during a second verification pass (field values were adjusted). */
    CORRECTED
}
