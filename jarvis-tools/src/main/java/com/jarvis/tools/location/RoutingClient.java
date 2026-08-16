package com.jarvis.tools.location;

import java.util.List;

/**
 * Computes real road-network routes and distance/duration matrices between coordinates.
 * Implementations must never throw on a clean "no route found" - only on genuine provider
 * failures (timeout, unreachable, 5xx).
 */
public interface RoutingClient {

    /**
     * Computes the road route between two points.
     *
     * @param from starting point
     * @param to destination point
     * @return the route outcome; {@code resolved=false} when no road route exists
     * @throws LocationException on a genuine provider failure
     */
    RouteResult route(GeoPoint from, GeoPoint to);

    /**
     * Computes an NxN road-network distance/duration matrix for the given points.
     *
     * @param points points to compute the matrix for, in the order they should appear in the result
     * @return the matrix outcome; individual unreachable pairs are represented as {@code null} cells
     * @throws LocationException on a genuine provider failure
     */
    RouteMatrixResult table(List<GeoPoint> points);
}
