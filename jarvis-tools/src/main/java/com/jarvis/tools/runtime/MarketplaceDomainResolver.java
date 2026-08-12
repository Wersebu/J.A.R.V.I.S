package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * Resolves marketplace aliases from user and model research text.
 */
public class MarketplaceDomainResolver {

    /**
     * Resolves a desired marketplace domain from text.
     *
     * @param text request text
     * @return desired domain when present
     */
    public Optional<String> resolve(String text) {
        String normalized = normalize(text);
        if (normalized.matches(".*\\bolx\\b.*")) {
            return Optional.of("olx.pl");
        }
        if (normalized.matches(".*\\ballegro\\s+lokalnie\\b.*") || normalized.matches(".*\\ballegrolokalnie\\b.*")) {
            return Optional.of("allegrolokalnie.pl");
        }
        if (normalized.matches(".*\\ballegro\\b.*")) {
            return Optional.of("allegro.pl");
        }
        if (normalized.matches(".*\\bceneo\\b.*")) {
            return Optional.of("ceneo.pl");
        }
        if (normalized.matches(".*\\bx-kom\\b.*") || normalized.matches(".*\\bxkom\\b.*")) {
            return Optional.of("x-kom.pl");
        }
        if (normalized.matches(".*\\bmorele\\b.*")) {
            return Optional.of("morele.net");
        }
        if (normalized.matches(".*\\bebay\\b.*")) {
            return Optional.of("ebay.");
        }
        return Optional.empty();
    }

    private String normalize(String value) {
        String noMarks = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT);
    }
}
