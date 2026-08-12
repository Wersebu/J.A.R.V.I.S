package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves marketplace aliases from user and model research text.
 */
public class MarketplaceDomainResolver {

    /**
     * Resolves all desired marketplace domains from text.
     *
     * @param text request text
     * @return domain constraint
     */
    public MarketplaceDomainConstraint resolveConstraint(String text) {
        String normalized = normalize(text);
        Set<String> domains = new LinkedHashSet<>();
        if (normalized.matches(".*\\bolx\\b.*")) {
            domains.add("olx.pl");
        }
        if (normalized.matches(".*\\ballegro\\s+lokalnie\\b.*") || normalized.matches(".*\\ballegrolokalnie\\b.*")) {
            domains.add("allegrolokalnie.pl");
        }
        if (normalized.matches(".*\\ballegro\\b.*")) {
            domains.add("allegro.pl");
        }
        if (normalized.matches(".*\\bceneo\\b.*")) {
            domains.add("ceneo.pl");
        }
        if (normalized.matches(".*\\bx-kom\\b.*") || normalized.matches(".*\\bxkom\\b.*")) {
            domains.add("x-kom.pl");
        }
        if (normalized.matches(".*\\bmorele\\b.*")) {
            domains.add("morele.net");
        }
        if (normalized.matches(".*\\bebay\\b.*")) {
            domains.add("ebay.");
        }
        return new MarketplaceDomainConstraint(domains);
    }

    /**
     * Resolves a desired marketplace domain from text.
     *
     * @param text request text
     * @return desired domain when present
     */
    public Optional<String> resolve(String text) {
        return resolveConstraint(text).allowedDomains().stream().findFirst();
    }

    private String normalize(String value) {
        String noMarks = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT);
    }
}
