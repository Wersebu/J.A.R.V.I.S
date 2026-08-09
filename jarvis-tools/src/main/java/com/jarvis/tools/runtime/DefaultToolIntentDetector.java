package com.jarvis.tools.runtime;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Lightweight Polish and English tool intent detector.
 */
@Service
public class DefaultToolIntentDetector implements ToolIntentDetector {

    @Override
    public ToolIntent detect(String message) {
        String value = normalize(message);
        if (containsAny(value, "usun", "delete", "skasuj")) {
            return ToolIntent.DELETE_KNOWLEDGE;
        }
        if (containsAny(value, "zaktualizuj", "update", "zmien", "popraw")) {
            return ToolIntent.UPDATE_DOCUMENT;
        }
        if (containsAny(value, "dopisz", "append", "dodaj do", "uzupelnij")) {
            return ToolIntent.APPEND_DOCUMENT;
        }
        if (containsAny(value, "stworz", "utworz", "create", "nowy dokument", "nowy plik", "plik wiedzy", "osobny dokument")) {
            return ToolIntent.CREATE_DOCUMENT;
        }
        if (containsAny(value, "zapisz", "save", "zapamietaj w wiedzy", "podziel", "podziel to")) {
            return ToolIntent.SAVE_KNOWLEDGE;
        }
        if (containsAny(value, "przeczytaj", "read", "co masz o", "pokaz dokument")) {
            return ToolIntent.READ_DOCUMENT;
        }
        if (containsAny(value, "internet", "w internecie", "web", "online", "aktualn", "cena", "ceny", "kurs", "notowan",
                "rynek", "wtorn",
                "news", "wiadomosci", "zloto", "zlot", "xau", "walut", "sprawdz w sieci", "wyszukaj w sieci",
                "latest", "current price", "price", "market", "exchange rate", "rate", "gold")) {
            return ToolIntent.SEARCH_WEB;
        }
        if (containsAny(value, "wyszukaj", "search", "znajdz w wiedzy", "szukaj",
                "jakie podzespoly", "jaka karte", "jakie gpu", "co wiesz o", "pamietasz")) {
            return ToolIntent.SEARCH_KNOWLEDGE;
        }
        if (containsAny(value, "uporzadkuj", "organize", "przenies", "rename", "move")) {
            return ToolIntent.ORGANIZE_KNOWLEDGE;
        }
        return ToolIntent.NO_TOOL;
    }

    private String normalize(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
