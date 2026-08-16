package com.jarvis.tools.location;

/**
 * NxN road-network distance/duration matrix between a list of points. Cells are boxed
 * {@link Double} rather than primitive {@code double} specifically so an unreachable pair (OSRM
 * returns {@code null} for those) is representable directly, without a magic-number sentinel.
 *
 * @param resolved whether the matrix request succeeded at all (a fully/partially unreachable
 *                 matrix is still {@code resolved=true} - individual unreachable cells are just
 *                 {@code null}; only a hard provider failure sets this to false)
 * @param distancesMeters {@code distancesMeters[i][j]} = road distance from point i to point j,
 *                        or {@code null} when unreachable
 * @param durationsSeconds {@code durationsSeconds[i][j]} = driving duration from point i to point j,
 *                         or {@code null} when unreachable
 * @param failureReason human-readable reason when {@code resolved} is false, empty otherwise
 */
public record RouteMatrixResult(
        boolean resolved,
        Double[][] distancesMeters,
        Double[][] durationsSeconds,
        String failureReason
) {

    /**
     * Builds a resolved matrix.
     */
    public static RouteMatrixResult resolved(Double[][] distancesMeters, Double[][] durationsSeconds) {
        return new RouteMatrixResult(true, distancesMeters, durationsSeconds, "");
    }

    /**
     * Builds an unresolved result carrying a reason.
     */
    public static RouteMatrixResult unresolved(String reason) {
        return new RouteMatrixResult(false, new Double[0][0], new Double[0][0], reason);
    }
}
