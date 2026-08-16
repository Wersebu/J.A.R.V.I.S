package com.jarvis.tools.location;

/**
 * One geocoding candidate returned by a provider for a query, with its structured address
 * components (when the provider supplies them) alongside the raw coordinate/display name -
 * {@link GeocodeCandidateScorer} matches these fields against the original query text instead of
 * relying on {@code displayName} alone, since a display name match tells you nothing about
 * whether the postal code, street, or region actually agree with what the user asked for.
 *
 * @param latitude candidate latitude
 * @param longitude candidate longitude
 * @param displayName provider's full normalized display name, for human-readable output only
 * @param postalCode structured postal code, or {@code ""} when the provider didn't supply one
 * @param city structured city/town/village/municipality name, or {@code ""}
 * @param street structured street/road name, or {@code ""}
 * @param houseNumber structured house number, or {@code ""}
 * @param region structured state/region/voivodeship name, or {@code ""}
 * @param country structured country name, or {@code ""}
 */
public record GeocodeCandidate(
        double latitude,
        double longitude,
        String displayName,
        String postalCode,
        String city,
        String street,
        String houseNumber,
        String region,
        String country
) {
}
