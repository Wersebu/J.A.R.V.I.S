package com.jarvis.common.run;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Public live lookup signals; local workspace and connected-system inspection remain separate. */
public final class PublicWebRequest {
    private PublicWebRequest() {}
    public static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replace('ł', 'l');
    }
    public static boolean requires(String text) {
        String value = normalize(text);
        if (value.matches("(?s).*\\b(plik|pliki|workspace|repozytorium|source code|connected|mcp|calendar|gmail)\\b.*")) return false;
        return dollarQuote(value) || value.matches("(?s).*\\b(pogod\\w*|weather|prognoz\\w*|internet\\w*|online|exchange rate|kurs|notowania|premier\\w*)\\b.*")
                || value.matches("(?s).*(kiedy wychodzi|release date|data wydania|wyszukaj w sieci).*?");
    }
    public static boolean exchangeRate(String text) {
        String value = normalize(text);
        if (value.matches("(?s).*\\b(xau|zloto|zlota|gold|btc|bitcoin)\\b.*")) return false;
        return dollarQuote(value) || value.matches("(?s).*\\b(kurs|exchange|rate)\\b.*") && value.matches("(?s).*\\b(usd|pln|eur|gbp|walut\\w*)\\b.*");
    }
    private static boolean dollarQuote(String normalized) {
        return normalized.matches("(?s).*ile kosztuje (dolar|euro)(?:[ ?.,!]|$).*");
    }
    public static List<String> independentQuestions(String text) {
        if (text == null || text.length() > 6000 || normalize(text).matches("(?s).*(na podstawie|nastepnie|based on|depending on|then use|nie deleguj|do not delegate).*")) return List.of();
        var questions = Arrays.stream(text.replaceFirst("(?iu)^\\s*jarvis[, :]+\\s*", "").split("(?iu)[?;\\n]+|,\\s*(?=(?:ile kosztuje|jaki jest|jaka jest)\\b)|\\s+i\\s+(?=(?:ile kosztuje|jaki jest|jaka jest|kiedy wychodzi)\\b)"))
                .map(String::strip).filter(s -> !s.isBlank()).toList();
        if (questions.size() < 2 || questions.size() > 3 || questions.stream().anyMatch(s -> s.length() > 2000 || !requires(s))) return List.of();
        return questions.stream().distinct().toList().size() == questions.size() ? questions : List.of();
    }
}
