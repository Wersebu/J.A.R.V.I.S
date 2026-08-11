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
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l');
    }
}
