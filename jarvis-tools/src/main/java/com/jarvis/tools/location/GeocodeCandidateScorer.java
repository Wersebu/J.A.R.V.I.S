package com.jarvis.tools.location;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the best geocoding candidate for a query, or reports that none can be picked confidently.
 * Pure logic, no network I/O - mirrors {@link RouteOptimizer}'s separation from its HTTP client.
 *
 * <p>The core rule this exists to enforce: a candidate whose name merely matches the query is not
 * enough - when the user provided a postal code (the strongest, least ambiguous signal available),
 * a candidate that contradicts it must lose to one that agrees, or - if no candidate agrees with
 * enough overall confidence - the query must come back {@link GeocodeStatus#NOT_CONFIDENTLY_RESOLVED}
 * rather than silently returning the wrong place.
 *
 * <p>Postal code contributes three ways, never a blind "mismatch = reject":
 * <ol>
 *   <li>query has a postal code, candidate's matches it -&gt; strong positive weight</li>
 *   <li>query has a postal code, candidate's is different -&gt; strong negative weight</li>
 *   <li>query has a postal code, candidate didn't report one -&gt; unknown, no contribution</li>
 * </ol>
 */
public class GeocodeCandidateScorer {

    private static final Logger LOGGER = LoggerFactory.getLogger(GeocodeCandidateScorer.class);

    private static final double POSTAL_CODE_MATCH = 100d;
    private static final double POSTAL_CODE_MISMATCH = -100d;
    private static final double CITY_MATCH = 40d;
    private static final double STREET_MATCH = 30d;
    private static final double HOUSE_NUMBER_MATCH = 25d;
    private static final double REGION_MATCH = 15d;
    private static final double COUNTRY_MATCH = 5d;
    private static final double PROVIDER_RANK_BONUS_STEP = 2d;
    private static final int PROVIDER_RANK_BONUS_CANDIDATES = 5;

    /** Minimum score a best candidate must reach to be trusted at all. */
    private static final double CONFIDENCE_MIN_SCORE = 20d;
    /** Minimum score gap over the runner-up required to avoid an AMBIGUOUS verdict. */
    private static final double AMBIGUITY_GAP = 15d;

    /**
     * Selects the best candidate for a raw query, or reports why none could be confidently selected.
     *
     * @param rawQuery original free-text query the candidates were fetched for
     * @param candidates provider candidates, in the provider's own relevance order
     * @return the geocode result: RESOLVED with the winning candidate, AMBIGUOUS/NOT_CONFIDENTLY_RESOLVED
     *         with the ranked candidate list for the caller to surface, or NOT_FOUND when the list is empty
     */
    public GeocodeResult select(String rawQuery, List<GeocodeCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            LOGGER.debug("[LOCATION] geocode query=\"{}\" candidates=0 status=NOT_FOUND", rawQuery);
            return GeocodeResult.notFound(rawQuery, "No matching location found");
        }
        AddressQuery query = AddressQuery.parse(rawQuery);
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            GeocodeCandidate candidate = candidates.get(index);
            scored.add(new Scored(candidate, score(query, candidate, index)));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<GeocodeCandidate> ranked = scored.stream().map(Scored::candidate).toList();

        Scored best = scored.get(0);
        double runnerUpScore = scored.size() > 1 ? scored.get(1).score() : Double.NEGATIVE_INFINITY;
        String postalOutcome = postalOutcome(query, best.candidate());

        GeocodeResult result;
        if (best.score() < CONFIDENCE_MIN_SCORE) {
            result = GeocodeResult.notConfidentlyResolved(rawQuery, ranked,
                    "No candidate matched the provided address details with enough confidence");
        } else if (scored.size() > 1 && (best.score() - runnerUpScore) < AMBIGUITY_GAP) {
            result = GeocodeResult.ambiguous(rawQuery, ranked);
        } else {
            result = GeocodeResult.resolved(rawQuery, best.candidate(), ranked);
        }

        LOGGER.debug("[LOCATION] geocode query=\"{}\" candidates={} selected=\"{}\" score={} status={} postalCode={}",
                rawQuery, candidates.size(), best.candidate().displayName(), best.score(), result.status(), postalOutcome);
        return result;
    }

    private double score(AddressQuery query, GeocodeCandidate candidate, int candidateIndex) {
        double score = 0d;
        if (query.hasPostalCode()) {
            String candidatePostalDigits = AddressQuery.normalizeDigits(candidate.postalCode());
            if (!candidatePostalDigits.isEmpty()) {
                score += candidatePostalDigits.equals(query.postalCodeDigits()) ? POSTAL_CODE_MATCH : POSTAL_CODE_MISMATCH;
            }
            // else: provider didn't report a postal code for this candidate - UNKNOWN, no contribution.
        }
        if (query.containsField(candidate.city())) {
            score += CITY_MATCH;
        }
        if (query.containsField(candidate.street())) {
            score += STREET_MATCH;
        }
        if (query.containsHouseNumber(candidate.houseNumber())) {
            score += HOUSE_NUMBER_MATCH;
        }
        if (query.containsField(candidate.region())) {
            score += REGION_MATCH;
        }
        if (query.containsField(candidate.country())) {
            score += COUNTRY_MATCH;
        }
        // Small tie-breaker favoring the provider's own relevance ranking when nothing else
        // distinguishes two candidates - never large enough to overcome a real signal above.
        score += Math.max(0, PROVIDER_RANK_BONUS_CANDIDATES - candidateIndex) * PROVIDER_RANK_BONUS_STEP;
        return score;
    }

    private String postalOutcome(AddressQuery query, GeocodeCandidate candidate) {
        if (!query.hasPostalCode()) {
            return "not-provided";
        }
        String candidatePostalDigits = AddressQuery.normalizeDigits(candidate.postalCode());
        if (candidatePostalDigits.isEmpty()) {
            return "unknown";
        }
        return candidatePostalDigits.equals(query.postalCodeDigits()) ? "match" : "mismatch";
    }

    private record Scored(GeocodeCandidate candidate, double score) {
    }
}
