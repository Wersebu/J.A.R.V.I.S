package com.jarvis.tools.runtime;

import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Lightweight Polish and English tool intent detector.
 */
@Service
public class DefaultToolIntentDetector implements ToolIntentDetector {

    @Override
    public ToolIntent detect(String message) {
        String value = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAny(value, "usuń", "usun", "delete", "skasuj")) {
            return ToolIntent.DELETE_KNOWLEDGE;
        }
        if (containsAny(value, "zaktualizuj", "update", "zmień", "zmien", "popraw")) {
            return ToolIntent.UPDATE_DOCUMENT;
        }
        if (containsAny(value, "dopisz", "append", "dodaj do", "uzupełnij", "uzupelnij")) {
            return ToolIntent.APPEND_DOCUMENT;
        }
        if (containsAny(value, "stwórz", "stworz", "utwórz", "utworz", "create", "nowy dokument", "osobny dokument")) {
            return ToolIntent.CREATE_DOCUMENT;
        }
        if (containsAny(value, "zapisz", "save", "zapamiętaj w wiedzy", "zapamietaj w wiedzy", "podziel", "podziel to")) {
            return ToolIntent.SAVE_KNOWLEDGE;
        }
        if (containsAny(value, "przeczytaj", "read", "co masz o", "pokaż dokument", "pokaz dokument")) {
            return ToolIntent.READ_DOCUMENT;
        }
        if (containsAny(value, "wyszukaj", "search", "znajdź w wiedzy", "znajdz w wiedzy", "szukaj",
                "jakie podzespoły", "jakie podzespoly", "jaką kartę", "jaka karte", "jakie gpu",
                "co wiesz o", "pamiętasz", "pamietasz")) {
            return ToolIntent.SEARCH_KNOWLEDGE;
        }
        if (containsAny(value, "uporządkuj", "uporzadkuj", "organize", "przenieś", "przenies", "rename", "move")) {
            return ToolIntent.ORGANIZE_KNOWLEDGE;
        }
        return ToolIntent.NO_TOOL;
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
