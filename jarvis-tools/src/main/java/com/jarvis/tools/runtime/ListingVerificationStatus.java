package com.jarvis.tools.runtime;

/**
 * Verification state for a marketplace listing candidate.
 */
public enum ListingVerificationStatus {

    /** Listing page was fetched successfully and bound to a price. */
    VERIFIED,
    /** Listing page no longer exists. */
    DEAD,
    /** Listing page could not be read because of access, rate limits or network limits. */
    BLOCKED,
    /** Listing page has not been read yet. */
    UNREAD,
    /** Listing page was read but did not match the requested entity or price requirements. */
    REJECTED
}
