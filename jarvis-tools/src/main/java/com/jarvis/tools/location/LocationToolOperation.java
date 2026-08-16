package com.jarvis.tools.location;

/**
 * Operations exposed by {@link LocationTool}.
 */
public enum LocationToolOperation {
    /** Resolves one or more free-text addresses/postal codes/cities to coordinates. */
    GEOCODE,
    /** Computes real road distance and duration between two points. */
    ROUTE,
    /** Computes an NxN road distance/duration matrix for a list of points - also covers what a
     *  model might call "DISTANCE_MATRIX"; there is deliberately only one canonical operation. */
    ROUTE_MATRIX,
    /** Orders a start point + a list of stops to reasonably minimize total distance or time. */
    OPTIMIZE_ROUTE
}
