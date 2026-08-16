package com.jarvis.tools.location;

/**
 * Turns a free-text address, postal code, or city into a coordinate. Implementations must never
 * throw on a clean "not found" - only on genuine provider failures (timeout, unreachable, 5xx) -
 * so batch callers can distinguish "this one address doesn't exist" from "the provider is down".
 */
public interface GeocodingClient {

    /**
     * Resolves one free-text query to a coordinate.
     *
     * @param query address, postal code, or city text
     * @return the geocode outcome; {@code resolved=false} for a clean "not found"
     * @throws LocationException on a genuine provider failure (timeout, unreachable, 5xx)
     */
    GeocodeResult geocode(String query);
}
