package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;
import com.jarvis.tools.web.WebUrlClassifier;

import java.math.BigDecimal;
import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts one atomic marketplace listing from a verified concrete page.
 */
public class MarketplaceListingExtractor {

    private static final Pattern STRUCTURED_PRICE = Pattern.compile(
            "(?iu)(?:structured data offer price|offer price|product price|price metadata|meta price)\\s*:?\\s*([0-9][0-9 .,\u00a0]{1,16})\\s*(PLN|z\\u0142|zl|EUR|USD)?"
    );
    private static final Pattern LABELED_PRICE = Pattern.compile(
            "(?iu)(?:cena|price|kwota)\\s*[:\\-]?\\s*([0-9][0-9 .,\u00a0]{1,16})\\s*(PLN|z\\u0142|zl|EUR|USD)"
    );
    private static final Pattern PRICE = Pattern.compile(
            "(?iu)(?<![\\w/])([1-9][0-9]{2,6}(?:[,.][0-9]{1,2})?)\\s*(PLN|z\\u0142|zl|EUR|USD)(?!\\w)"
    );
    private static final Set<String> VALUE_WORDS = Set.of(
            "cena", "ceny", "koszt", "kosztuje", "po", "ile", "price", "prices", "market",
            "used", "uzywan", "uzywane", "uzywana", "oferta", "oferty", "listing", "link",
            "aktualny", "aktualna", "current", "retrieve", "search", "marketplace"
    );
    private final WebEntityMatcher entityMatcher = new WebEntityMatcher();

    /**
     * Extracts a verified listing from a successful READ_WEB_PAGE result.
     *
     * @param request request
     * @param result result
     * @return listing when the page contains one matching priced offer
     */
    public Optional<MarketplaceListing> extract(ToolCallingRequest request, ToolResult result) {
        String url = text(result.data().get("url"));
        if (!WebUrlClassifier.isConcreteListing(url)) {
            return Optional.empty();
        }
        int statusCode = statusCode(result);
        if (statusCode < 200 || statusCode >= 300) {
            return Optional.empty();
        }
        String title = text(result.data().get("title"));
        String content = text(result.data().get("content"));
        if ((title + content).isBlank()) {
            return Optional.empty();
        }
        EntityDescriptor requestedEntity = entityMatcher.describe(request.userMessage() + " " + request.goal() + " " + request.reason());
        String entityText = title + " " + firstCharacters(content, 2000);
        EntityMatchResult match = entityMatcher.match(requestedEntity, entityText);
        if (!match.accepted() || !tokenEntityMatch(request, entityText)) {
            return Optional.empty();
        }
        Optional<PriceCandidate> price = bestPrice(content)
                .or(() -> bestPrice(title));
        if (price.isEmpty()) {
            return Optional.empty();
        }
        PriceCandidate value = price.get();
        String resolvedTitle = title.isBlank() ? titleFromUrl(url) : title;
        String domain = WebUrlClassifier.domain(url);
        return Optional.of(new MarketplaceListing(
                resolvedTitle,
                value.amount(),
                value.currency(),
                condition(request, title + " " + content),
                domain,
                url,
                statusCode,
                true,
                Instant.now(),
                ListingVerificationStatus.VERIFIED,
                Math.min(0.98d, value.confidence() + match.score() * 0.08d)
        ));
    }

    private Optional<PriceCandidate> bestPrice(String content) {
        List<PriceCandidate> candidates = new ArrayList<>();
        collect(candidates, STRUCTURED_PRICE, content, 0.92d);
        collect(candidates, LABELED_PRICE, content, 0.84d);
        collect(candidates, PRICE, firstCharacters(content, 6000), 0.72d);
        return candidates.stream()
                .filter(candidate -> candidate.amount().compareTo(BigDecimal.valueOf(50)) >= 0)
                .filter(candidate -> candidate.amount().compareTo(BigDecimal.valueOf(250_000)) <= 0)
                .max(Comparator.comparingDouble(PriceCandidate::confidence));
    }

    private void collect(List<PriceCandidate> candidates, Pattern pattern, String content, double confidence) {
        Matcher matcher = pattern.matcher(content == null ? "" : content);
        while (matcher.find() && candidates.size() < 20) {
            parseAmount(matcher.group(1)).ifPresent(amount ->
                    candidates.add(new PriceCandidate(amount, currency(matcher.group(2)), confidence)));
        }
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

    private String condition(ToolCallingRequest request, String content) {
        if ("USED".equalsIgnoreCase(requestCondition(request))) {
            return "USED";
        }
        String normalized = normalize(content);
        if (normalized.contains("uzywan") || normalized.contains("used")) {
            return "USED";
        }
        if (normalized.contains("nowy") || normalized.contains("new")) {
            return "NEW";
        }
        return "UNKNOWN";
    }

    private String requestCondition(ToolCallingRequest request) {
        return ResearchRequirements.from(request).condition();
    }

    private boolean tokenEntityMatch(ToolCallingRequest request, String content) {
        String normalized = normalize(content);
        List<String> terms = new ArrayList<>();
        String requestText = normalize(request.userMessage() + " " + request.goal());
        for (String token : requestText.split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !VALUE_WORDS.contains(token)) {
                terms.add(token);
            }
        }
        if (terms.isEmpty()) {
            return true;
        }
        long matches = terms.stream().distinct().filter(normalized::contains).count();
        return matches >= Math.min(2, terms.stream().distinct().count());
    }

    private int statusCode(ToolResult result) {
        Object value = result.data().get("statusCode");
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(Objects.toString(value, "200"));
        } catch (NumberFormatException exception) {
            return 200;
        }
    }

    private String currency(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || normalized.equals("zl") || normalized.startsWith("z")) {
            return "PLN";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String titleFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) {
                return url;
            }
            String leaf = path.substring(path.lastIndexOf('/') + 1)
                    .replaceAll("-CID\\d+-.*$", "")
                    .replaceAll("\\.html$", "")
                    .replace('-', ' ')
                    .strip();
            return leaf.isBlank() ? url : leaf;
        } catch (RuntimeException exception) {
            return url;
        }
    }

    private String firstCharacters(String value, int max) {
        String text = Objects.toString(value, "");
        return text.length() <= max ? text : text.substring(0, max);
    }

    private String normalize(String value) {
        String noMarks = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }

    private record PriceCandidate(BigDecimal amount, String currency, double confidence) {
    }
}
