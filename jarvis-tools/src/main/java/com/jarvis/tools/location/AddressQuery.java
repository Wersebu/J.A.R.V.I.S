package com.jarvis.tools.location;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsed view of a raw free-text geocoding query, used by {@link GeocodeCandidateScorer} to check
 * how well a candidate's structured address fields actually agree with what the user typed -
 * rather than trusting that a provider's top result is correct just because its name looks right.
 *
 * <p>Deliberately lightweight: this does not attempt to fully parse an address into
 * street/city/postal-code slots (that's exactly the job the provider's own candidates already do
 * for us). It only extracts a Polish-format postal code (the strongest, least ambiguous signal a
 * user can give) and normalizes the rest of the text for substring/word matching against each
 * candidate's fields.
 */
final class AddressQuery {

    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("\\b(\\d{2})[- ]?(\\d{3})\\b");

    private final String postalCode;
    private final String normalizedText;

    private AddressQuery(String postalCode, String normalizedText) {
        this.postalCode = postalCode;
        this.normalizedText = normalizedText;
    }

    static AddressQuery parse(String rawQuery) {
        String raw = rawQuery == null ? "" : rawQuery;
        Matcher matcher = POSTAL_CODE_PATTERN.matcher(raw);
        String postalCode = matcher.find() ? matcher.group(1) + matcher.group(2) : null;
        return new AddressQuery(postalCode, normalize(raw));
    }

    boolean hasPostalCode() {
        return postalCode != null;
    }

    /**
     * Returns the query's postal code as bare digits (e.g. {@code "05500"}), or {@code null} when
     * the query didn't contain one.
     */
    String postalCodeDigits() {
        return postalCode;
    }

    /**
     * Whether the query text contains the given candidate field as a whole, word-boundary-delimited
     * phrase - used for multi-word fields like city/street/region names. Word-boundary matching
     * (not a plain substring check) matters here: e.g. the street "Warszawska" contains "Warszawa"
     * as a raw substring, which would otherwise falsely credit a city match for the wrong city.
     */
    boolean containsField(String candidateValue) {
        return containsToken(candidateValue);
    }

    /**
     * Whether the query text contains the given house number as a whole, word-boundary-delimited
     * token - same matching rule as {@link #containsField}, kept as a separate method name for
     * readability at call sites (a house number is conceptually different from a name field, even
     * though the matching logic is identical).
     */
    boolean containsHouseNumber(String houseNumber) {
        return containsToken(houseNumber);
    }

    private boolean containsToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalizedValue = normalize(value);
        if (normalizedValue.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizedValue) + "(?![a-z0-9])").matcher(normalizedText).find();
    }

    static String normalizeDigits(String value) {
        return value == null ? "" : value.replaceAll("\\D", "");
    }

    private static String normalize(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        String stripped = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.replaceAll("[^a-z0-9]+", " ").trim().replaceAll("\\s+", " ");
    }
}
