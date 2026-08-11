package com.jarvis.tools.runtime;

/**
 * Required freshness level for information needed to answer a user request.
 */
public enum InformationFreshness {

    /**
     * Stable conceptual information that can be answered without live sources.
     */
    STATIC,

    /**
     * Information that may benefit from live sources depending on the exact context.
     */
    MAY_REQUIRE_LIVE,

    /**
     * Time-sensitive information that must be grounded in live evidence.
     */
    MUST_BE_LIVE
}
