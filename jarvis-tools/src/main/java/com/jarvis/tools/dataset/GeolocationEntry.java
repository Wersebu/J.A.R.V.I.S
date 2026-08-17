package com.jarvis.tools.dataset;

/**
 * One geolocation result to apply to an existing {@link StoreRecord}, used by
 * {@link StoreAuditDatasetService#updateGeolocation}. References an existing record by id - never
 * creates a new one.
 *
 * @param recordId id of the record to update
 * @param status resolved geolocation status
 * @param latitude resolved latitude, or null when unresolved
 * @param longitude resolved longitude, or null when unresolved
 */
public record GeolocationEntry(
        String recordId,
        GeolocationStatus status,
        Double latitude,
        Double longitude
) {
}
