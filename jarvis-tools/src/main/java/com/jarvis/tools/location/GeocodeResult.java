package com.jarvis.tools.location;

/**
 * Outcome of resolving one free-text query (address, postal code, or city) to a coordinate.
 * Always non-throwing on a clean "not found" - {@code resolved=false} with a {@code failureReason}
 * is the normal way to represent that, so batch callers can report partial success instead of
 * failing an entire multi-address request over one bad entry.
 *
 * @param resolved whether the query was successfully geocoded
 * @param query the original free-text query
 * @param latitude resolved latitude, meaningless when {@code resolved} is false
 * @param longitude resolved longitude, meaningless when {@code resolved} is false
 * @param displayName the provider's normalized display name for the resolved place
 * @param failureReason human-readable reason when {@code resolved} is false, empty otherwise
 */
public record GeocodeResult(
        boolean resolved,
        String query,
        double latitude,
        double longitude,
        String displayName,
        String failureReason
) {

    /**
     * Builds a resolved result.
     */
    public static GeocodeResult resolved(String query, double latitude, double longitude, String displayName) {
        return new GeocodeResult(true, query, latitude, longitude, displayName, "");
    }

    /**
     * Builds an unresolved result carrying a reason.
     */
    public static GeocodeResult unresolved(String query, String reason) {
        return new GeocodeResult(false, query, 0d, 0d, "", reason);
    }

    /**
     * Converts a resolved result to a {@link GeoPoint}, labeled with the original query.
     */
    public GeoPoint toGeoPoint() {
        return new GeoPoint(latitude, longitude, query);
    }
}
