package com.jarvis.tools.location;

/**
 * Outcome classification for one geocoded query, produced by {@link GeocodeCandidateScorer}.
 */
public enum GeocodeStatus {
    /** A single candidate clearly matched the query's address details with enough confidence. */
    RESOLVED,
    /** Two or more candidates scored too close together to confidently pick one - needs clarification. */
    AMBIGUOUS,
    /** The best candidate's match score was too low to trust, even without a close competitor
     *  (e.g. the only candidate contradicts a postal code the user actually provided). */
    NOT_CONFIDENTLY_RESOLVED,
    /** The provider returned no candidates at all. */
    NOT_FOUND
}
