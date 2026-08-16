package com.jarvis.tools.location;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link GeocodeCandidateScorer} - no HTTP, exactly the scenarios from the
 * "wrong location chosen for a matching-name-but-wrong-region address" bug report.
 */
class GeocodeCandidateScorerTest {

    private final GeocodeCandidateScorer scorer = new GeocodeCandidateScorer();

    @Test
    void postalCodeMatchWinsOverNameOnlyMatchInADifferentRegion() {
        // TEST 1
        GeocodeCandidate wrongRegion = candidate(53.0014, 23.6267, "Nowa Wola", "16-050", "podlaskie");
        GeocodeCandidate correctRegion = candidate(51.75, 21.65, "Nowa Wola", "05-500", "mazowieckie");

        GeocodeResult result = scorer.select("Nowa Wola 05-500", List.of(wrongRegion, correctRegion));

        assertThat(result.status()).isEqualTo(GeocodeStatus.RESOLVED);
        assertThat(result.latitude()).isEqualTo(51.75);
        assertThat(result.longitude()).isEqualTo(21.65);
    }

    @Test
    void fullAddressMatchWinsOverStreetAndHouseNumberMatchInTheWrongCity() {
        // TEST 2
        GeocodeCandidate wrongCity = new GeocodeCandidate(52.23, 21.01, "Warszawska 19, Warszawa",
                "00-001", "Warszawa", "Warszawska", "19", "mazowieckie", "Polska");
        GeocodeCandidate correctAddress = new GeocodeCandidate(51.78, 21.20, "Warszawska 19, Warka",
                "05-660", "Warka", "Warszawska", "19", "mazowieckie", "Polska");

        GeocodeResult result = scorer.select("Warszawska 19, Warka, 05-660", List.of(wrongCity, correctAddress));

        assertThat(result.status()).isEqualTo(GeocodeStatus.RESOLVED);
        assertThat(result.latitude()).isEqualTo(51.78);
        assertThat(result.longitude()).isEqualTo(21.20);
    }

    @Test
    void aCandidateIsNeverRejectedJustBecauseTheProviderDidNotReportAPostalCode() {
        // TEST 3
        GeocodeCandidate noPostalCode = new GeocodeCandidate(51.75, 21.65, "Nowa Wola, Mazowieckie",
                "", "Nowa Wola", "", "", "mazowieckie", "Polska");

        GeocodeResult result = scorer.select("Nowa Wola 05-500", List.of(noPostalCode));

        assertThat(result.status()).isEqualTo(GeocodeStatus.RESOLVED);
        assertThat(result.latitude()).isEqualTo(51.75);
    }

    @Test
    void anExplicitlyDifferentPostalCodeIsHeavilyPenalizedEvenAsTheOnlyCandidate() {
        // TEST 4
        GeocodeCandidate wrongPostalCode = candidate(53.0014, 23.6267, "Nowa Wola", "16-050", "podlaskie");

        GeocodeResult result = scorer.select("Nowa Wola 05-500", List.of(wrongPostalCode));

        assertThat(result.status()).isNotEqualTo(GeocodeStatus.RESOLVED);
        assertThat(result.resolved()).isFalse();
    }

    @Test
    void twoSameNamedPlacesWithNoDistinguishingDataAreNeverArbitrarilyResolved() {
        // TEST 5
        GeocodeCandidate first = candidateWithoutPostalCode(52.0, 20.0, "Nowa Wola", "podlaskie");
        GeocodeCandidate second = candidateWithoutPostalCode(51.0, 21.0, "Nowa Wola", "mazowieckie");

        GeocodeResult result = scorer.select("Nowa Wola", List.of(first, second));

        assertThat(result.status()).isNotEqualTo(GeocodeStatus.RESOLVED);
        assertThat(result.resolved()).isFalse();
        assertThat(result.candidates()).hasSize(2);
    }

    @Test
    void emptyCandidateListIsReportedAsNotFound() {
        GeocodeResult result = scorer.select("Nieistniejacy Adres", List.of());

        assertThat(result.status()).isEqualTo(GeocodeStatus.NOT_FOUND);
        assertThat(result.resolved()).isFalse();
    }

    @Test
    void ambiguousAndNotConfidentResultsStillCarryTheirCandidateListForClarification() {
        GeocodeCandidate first = candidateWithoutPostalCode(52.0, 20.0, "Nowa Wola", "podlaskie");
        GeocodeCandidate second = candidateWithoutPostalCode(51.0, 21.0, "Nowa Wola", "mazowieckie");

        GeocodeResult result = scorer.select("Nowa Wola", List.of(first, second));

        assertThat(result.candidates()).extracting(GeocodeCandidate::region)
                .containsExactlyInAnyOrder("podlaskie", "mazowieckie");
    }

    private GeocodeCandidate candidate(double lat, double lon, String city, String postalCode, String region) {
        return new GeocodeCandidate(lat, lon, city + ", " + region, postalCode, city, "", "", region, "Polska");
    }

    private GeocodeCandidate candidateWithoutPostalCode(double lat, double lon, String city, String region) {
        return new GeocodeCandidate(lat, lon, city + ", " + region, "", city, "", "", region, "Polska");
    }
}
