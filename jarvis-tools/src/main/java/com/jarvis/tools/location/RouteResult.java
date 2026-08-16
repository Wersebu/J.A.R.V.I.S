package com.jarvis.tools.location;

/**
 * Outcome of computing a real road route between two points.
 *
 * @param resolved whether a route was found
 * @param distanceMeters road distance in meters, meaningless when {@code resolved} is false
 * @param durationSeconds estimated driving duration in seconds, meaningless when {@code resolved} is false
 * @param failureReason human-readable reason when {@code resolved} is false, empty otherwise
 */
public record RouteResult(boolean resolved, double distanceMeters, double durationSeconds, String failureReason) {

    /**
     * Builds a resolved result.
     */
    public static RouteResult resolved(double distanceMeters, double durationSeconds) {
        return new RouteResult(true, distanceMeters, durationSeconds, "");
    }

    /**
     * Builds an unresolved result carrying a reason.
     */
    public static RouteResult unresolved(String reason) {
        return new RouteResult(false, 0d, 0d, reason);
    }
}
