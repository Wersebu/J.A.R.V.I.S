package com.jarvis.tools.runtime;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Classifies whether a request requires live/current evidence.
 */
@Service
public class InformationFreshnessEvaluator {

    private static final Set<String> LIVE_TERMS = Set.of(
            "aktualn", "obecn", "teraz", "dzisiaj", "najnowsz", "ostatni",
            "cena", "ceny", "koszt", "kosztuje", "po ile", "ile chodzi",
            "kurs", "notowan", "rynek", "wtorn", "uzywan", "dostepnosc",
            "premiera", "wyszla", "wydana", "wiadomosci",
            "current", "latest", "today", "now", "price", "prices", "rate",
            "market", "used", "secondary", "released", "availability", "news"
    );

    private static final Set<String> STATIC_TERMS = Set.of(
            "co to jest", "jak dziala", "wyjasnij", "definicja", "czym jest",
            "what is", "how does", "explain", "definition"
    );

    /**
     * Evaluates freshness for a user request and model/tool context.
     *
     * @param userMessage latest user message
     * @param goal tool goal or empty
     * @param reason tool reason or empty
     * @return required freshness level
     */
    public InformationFreshness evaluate(String userMessage, String goal, String reason) {
        String text = normalize(userMessage + " " + goal + " " + reason);
        if (text.isBlank()) {
            return InformationFreshness.STATIC;
        }
        if (containsAny(text, LIVE_TERMS)) {
            return InformationFreshness.MUST_BE_LIVE;
        }
        if (containsAny(text, STATIC_TERMS)) {
            return InformationFreshness.STATIC;
        }
        if (text.matches(".*\\b(version|wersja|release|premier[aey]?|model|produkt)\\b.*")) {
            return InformationFreshness.MAY_REQUIRE_LIVE;
        }
        return InformationFreshness.STATIC;
    }

    private boolean containsAny(String value, Set<String> terms) {
        for (String term : terms) {
            if (containsWord(value, term)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reports whether {@code term} occurs in {@code value} at a real word boundary on both sides -
     * plain {@link String#contains} matches inside an unrelated longer word (e.g. the English term
     * "now" inside the Polish place name "Nowej", as in "Nowa Wola" - the exact false positive that
     * misclassified a plain schedule-creation request as requiring live web evidence and drove the
     * tool loop into an unbounded "live evidence required" retry cycle). {@code term} may itself
     * contain spaces (e.g. "po ile") - a space is not a letter/digit, so the boundary check still
     * holds at each end.
     *
     * @param value normalized haystack text
     * @param term normalized term to look for
     * @return true when {@code term} appears as a whole-word (or whole-phrase) match
     */
    private boolean containsWord(String value, String term) {
        int fromIndex = 0;
        while (true) {
            int index = value.indexOf(term, fromIndex);
            if (index < 0) {
                return false;
            }
            boolean leftBoundary = index == 0 || !Character.isLetterOrDigit(value.charAt(index - 1));
            int endIndex = index + term.length();
            boolean rightBoundary = endIndex == value.length() || !Character.isLetterOrDigit(value.charAt(endIndex));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            fromIndex = index + 1;
        }
    }

    private String normalize(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l');
    }
}
