package com.jarvis.tools.runtime;

import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts runtime market observations from search snippets and page text.
 */
public class MarketObservationExtractor {

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?iu)(?:\\b(\\d{1,3}(?:[ .\\u00a0]?\\d{3})+|\\d{2,6})(?:[,.](\\d{1,2}))?\\s*(zł|zl|pln|usd|eur|gbp)\\b|([$€])\\s*(\\d{1,6}(?:[,.]\\d{1,2})?))"
    );
    private static final Set<String> MARKET_VALUE_TERMS = Set.of(
            "cena", "ceny", "koszt", "kosztuje", "po ile", "ile chodzi",
            "price", "prices", "rate", "exchange", "kurs", "notowan"
    );
    private static final Set<String> MARKET_CONTEXT_TERMS = Set.of("rynek", "wtorn");

    /**
     * Extracts observations from text when the value belongs to the searched entity.
     *
     * @param request tool request
     * @param title title
     * @param snippetOrContent snippet or page content
     * @param source source or domain
     * @param url URL
     * @return observations
     */
    public List<MarketObservation> extract(ToolCallingRequest request, String title, String snippetOrContent, String source, String url) {
        String text = compact(title + " " + snippetOrContent);
        if (text.isBlank() || !requiresMarketValue(request)) {
            return List.of();
        }
        if (!entityMatches(request, text)) {
            return List.of();
        }
        List<MarketObservation> observations = new ArrayList<>();
        Matcher matcher = PRICE_PATTERN.matcher(text);
        while (matcher.find()) {
            parsePrice(matcher).ifPresent(price -> observations.add(new MarketObservation(
                    entityName(request),
                    variantName(request),
                    title == null ? "" : title.strip(),
                    price.amount(),
                    price.currency(),
                    condition(text),
                    sourceName(source, url),
                    url == null ? "" : url,
                    Instant.now(),
                    confidence(text, source, url),
                    false
            )));
        }
        return observations;
    }

    /**
     * Returns whether the request asks for market/current numeric values.
     *
     * @param request tool request
     * @return true when market/value evidence is needed
     */
    public boolean requiresMarketValue(ToolCallingRequest request) {
        String text = normalize(request.userMessage() + " " + request.goal() + " " + request.reason());
        return MARKET_VALUE_TERMS.stream().anyMatch(text::contains)
                || MARKET_CONTEXT_TERMS.stream().anyMatch(text::contains);
    }

    private Optional<ParsedPrice> parsePrice(Matcher matcher) {
        String symbol = matcher.group(5);
        if (symbol != null && !symbol.isBlank()) {
            return parseAmount(matcher.group(6)).map(amount -> new ParsedPrice(amount, "$".equals(symbol) ? "USD" : "EUR"));
        }
        String currency = matcher.group(3);
        return parseAmount(matcher.group(1) + (matcher.group(2) == null ? "" : "." + matcher.group(2)))
                .map(amount -> new ParsedPrice(amount, normalizeCurrency(currency)));
    }

    private Optional<BigDecimal> parseAmount(String raw) {
        String value = raw == null ? "" : raw.replace("\u00a0", " ").strip();
        if (value.isBlank()) {
            return Optional.empty();
        }
        value = value.replace(" ", "");
        if (value.contains(",") && value.contains(".")) {
            value = value.replace(".", "").replace(",", ".");
        } else if (value.contains(",")) {
            value = value.replace(",", ".");
        } else if (value.matches("\\d{1,3}(\\.\\d{3})+")) {
            value = value.replace(".", "");
        }
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private boolean entityMatches(ToolCallingRequest request, String text) {
        Set<String> terms = new java.util.LinkedHashSet<>();
        String normalized = normalize(request.userMessage() + " " + request.goal());
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() >= 3
                    && !MARKET_VALUE_TERMS.contains(token)
                    && !MARKET_CONTEXT_TERMS.contains(token)
                    && !Set.of("ile", "jaka", "jakie", "polska", "pln", "used", "uzywan", "uzywane").contains(token)) {
                terms.add(token);
            }
        }
        if (terms.isEmpty()) {
            return true;
        }
        String haystack = normalize(text);
        long matches = terms.stream().filter(haystack::contains).count();
        return matches >= Math.min(2, terms.size());
    }

    private String entityName(ToolCallingRequest request) {
        String text = compact(request.goal().isBlank() ? request.userMessage() : request.goal());
        return text.length() <= 90 ? text : text.substring(0, 90).strip();
    }

    private String variantName(ToolCallingRequest request) {
        Matcher matcher = Pattern.compile("(?i)\\b(\\d{1,3}\\s*(?:gb|gib)|ti|super|xt|aorus|eagle)\\b").matcher(request.userMessage() + " " + request.goal());
        List<String> variants = new ArrayList<>();
        while (matcher.find()) {
            variants.add(matcher.group().replaceAll("\\s+", ""));
        }
        return String.join(" ", variants);
    }

    private double confidence(String text, String source, String url) {
        double confidence = 0.65d;
        String normalized = normalize(text + " " + source + " " + url);
        if (normalized.contains("olx") || normalized.contains("allegro") || normalized.contains("ebay")) {
            confidence += 0.15d;
        }
        if (normalized.contains("uzywan") || normalized.contains("used")) {
            confidence += 0.05d;
        }
        return Math.min(0.95d, confidence);
    }

    private String condition(String text) {
        String normalized = normalize(text);
        if (normalized.contains("uzywan") || normalized.contains("used")) {
            return "used";
        }
        if (normalized.contains("nowy") || normalized.contains("new")) {
            return "new";
        }
        return "";
    }

    private String sourceName(String source, String url) {
        if (source != null && !source.isBlank()) {
            return source.strip();
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "").toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "";
        }
    }

    private String normalizeCurrency(String value) {
        String normalized = normalize(value);
        if (normalized.equals("zl")) {
            return "PLN";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String normalize(String value) {
        String noMarks = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l');
        return noMarks.toLowerCase(Locale.ROOT);
    }

    private record ParsedPrice(BigDecimal amount, String currency) {
    }
}
