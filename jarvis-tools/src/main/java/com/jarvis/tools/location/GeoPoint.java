package com.jarvis.tools.location;

/**
 * A resolved geographic coordinate, optionally labeled with the address/query it came from.
 *
 * @param latitude latitude in degrees
 * @param longitude longitude in degrees
 * @param label the original address/query, or a caller-supplied name, kept for display purposes
 */
public record GeoPoint(double latitude, double longitude, String label) {
}
