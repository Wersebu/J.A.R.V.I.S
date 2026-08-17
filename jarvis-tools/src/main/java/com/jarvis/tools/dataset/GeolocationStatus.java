package com.jarvis.tools.dataset;

/**
 * Geolocation state of a single {@link StoreRecord}.
 */
public enum GeolocationStatus {
    /** Geolocation has not been attempted yet. */
    PENDING,
    /** Coordinates were resolved with confidence. */
    RESOLVED,
    /** Multiple candidate locations were found; not confidently resolved. */
    AMBIGUOUS,
    /** Geolocation was attempted and failed. */
    FAILED
}
