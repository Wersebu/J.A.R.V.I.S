package com.jarvis.tools.location;

import java.util.List;

/**
 * Outcome of resolving one free-text query (address, postal code, or city) to a coordinate.
 * Always non-throwing on a clean "not found"/"ambiguous"/"not confident" outcome - {@link #resolved()}
 * being {@code false} with a {@link #failureReason()} is the normal way to represent that, so batch
 * callers can report partial success instead of failing an entire multi-address request over one
 * bad entry, and so an uncertain match is never silently reported as if it were certain.
 *
 * @param status how confidently this query was resolved - see {@link GeocodeStatus}
 * @param query the original free-text query
 * @param latitude resolved latitude, only meaningful when {@code status} is {@link GeocodeStatus#RESOLVED}
 * @param longitude resolved longitude, only meaningful when {@code status} is {@link GeocodeStatus#RESOLVED}
 * @param displayName the provider's normalized display name for the resolved place, empty otherwise
 * @param failureReason human-readable reason when not resolved, empty otherwise
 * @param candidates the scored candidates considered (best first) - populated for every status except
 *                    {@link GeocodeStatus#NOT_FOUND}, so an AMBIGUOUS/NOT_CONFIDENTLY_RESOLVED result
 *                    still gives the caller something concrete to show the user for clarification
 */
public record GeocodeResult(
        GeocodeStatus status,
        String query,
        double latitude,
        double longitude,
        String displayName,
        String failureReason,
        List<GeocodeCandidate> candidates
) {

    public GeocodeResult {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /**
     * Whether this query was resolved with enough confidence to trust the coordinates.
     */
    public boolean resolved() {
        return status == GeocodeStatus.RESOLVED;
    }

    /**
     * Builds a confidently resolved result.
     */
    public static GeocodeResult resolved(String query, GeocodeCandidate winner, List<GeocodeCandidate> allCandidates) {
        return new GeocodeResult(GeocodeStatus.RESOLVED, query, winner.latitude(), winner.longitude(),
                winner.displayName(), "", allCandidates);
    }

    /**
     * Convenience factory for callers (tests, simple fakes) that don't need candidate-level detail.
     */
    public static GeocodeResult resolved(String query, double latitude, double longitude, String displayName) {
        return new GeocodeResult(GeocodeStatus.RESOLVED, query, latitude, longitude, displayName, "", List.of());
    }

    /**
     * Builds an AMBIGUOUS result: two or more candidates scored too close together to confidently
     * pick one. The caller should surface {@code candidates} so the model can ask the user to
     * clarify, rather than guessing.
     */
    public static GeocodeResult ambiguous(String query, List<GeocodeCandidate> candidates) {
        return new GeocodeResult(GeocodeStatus.AMBIGUOUS, query, 0d, 0d, "",
                "Multiple similarly-ranked locations matched this query - clarification needed", candidates);
    }

    /**
     * Builds a NOT_CONFIDENTLY_RESOLVED result: the best candidate's score was too low to trust
     * (e.g. it contradicts a postal code the user provided), even though it was the only option.
     */
    public static GeocodeResult notConfidentlyResolved(String query, List<GeocodeCandidate> candidates, String reason) {
        return new GeocodeResult(GeocodeStatus.NOT_CONFIDENTLY_RESOLVED, query, 0d, 0d, "", reason, candidates);
    }

    /**
     * Builds a NOT_FOUND result: the provider returned no candidates at all.
     */
    public static GeocodeResult notFound(String query, String reason) {
        return new GeocodeResult(GeocodeStatus.NOT_FOUND, query, 0d, 0d, "", reason, List.of());
    }

    /**
     * Generic "not resolved, here's why" convenience factory for simple fakes/tests that don't
     * need to distinguish NOT_FOUND from the other non-resolved statuses.
     */
    public static GeocodeResult unresolved(String query, String reason) {
        return notFound(query, reason);
    }

    /**
     * Converts a resolved result to a {@link GeoPoint}, labeled with the original query.
     */
    public GeoPoint toGeoPoint() {
        return new GeoPoint(latitude, longitude, query);
    }
}
