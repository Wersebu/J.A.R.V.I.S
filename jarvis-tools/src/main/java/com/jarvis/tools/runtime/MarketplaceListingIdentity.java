package com.jarvis.tools.runtime;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes a canonical identity for a marketplace listing so the same real-world auction is never
 * counted twice, regardless of which URL variant (desktop, mobile, tracking-decorated) surfaced it.
 *
 * <p>Identity is resolved in priority order, general across marketplaces rather than tied to one
 * domain: (1) a stable listing id extracted from the URL when the marketplace embeds one in a
 * recognizable pattern, scoped to the normalized host so ids never collide across domains;
 * (2) the canonical URL (scheme, host with www/mobile prefixes stripped, path with trailing slash
 * stripped, query/fragment/tracking params removed); (3) normalized title + price + source, for
 * listings with no usable URL at all.
 */
public final class MarketplaceListingIdentity {

    private static final Pattern MOBILE_HOST_PREFIX = Pattern.compile("^(?:www|m|mobile|amp)\\.");
    private static final Pattern TRAILING_ID_SEGMENT = Pattern.compile("(?i)-id([a-z0-9]+)(?:\\.html?)?/?$");
    private static final Pattern TRAILING_NUMERIC_ID = Pattern.compile("(?i)[-/](\\d{6,})(?:\\.html?)?/?$");

    private MarketplaceListingIdentity() {
    }

    /**
     * Resolves the canonical identity for a listing URL, preferring a stable listing id.
     *
     * @param url listing URL
     * @return canonical identity string, or "" when the URL is unusable
     */
    public static String forUrl(String url) {
        String canonical = canonicalUrl(url);
        if (canonical.isBlank()) {
            return "";
        }
        String host = hostOf(url);
        Optional<String> listingId = listingId(canonical);
        return listingId.map(id -> host + "#listing:" + id).orElse(canonical);
    }

    /**
     * Fallback identity for listings without a usable URL: normalized title + price + source.
     *
     * @param title listing title
     * @param price listing price, may be null
     * @param source listing source/domain
     * @return fallback identity string
     */
    public static String forTitlePriceSource(String title, BigDecimal price, String source) {
        return normalizeText(title) + "|" + (price == null ? "" : price.stripTrailingZeros().toPlainString()) + "|" + normalizeText(source);
    }

    /**
     * Normalizes a URL to a canonical, comparable form: lowercase host with www/mobile prefixes
     * stripped, path with trailing slash stripped, no query string, no fragment.
     *
     * @param url raw URL
     * @return canonical URL, or "" when the URL cannot be parsed
     */
    public static String canonicalUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = new URI(url.strip());
            String host = hostOf(url);
            if (host.isBlank()) {
                return "";
            }
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return host + path;
        } catch (URISyntaxException exception) {
            return url.split("[?#]")[0].strip();
        }
    }

    private static String hostOf(String url) {
        try {
            URI uri = new URI(url.strip());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            return MOBILE_HOST_PREFIX.matcher(host.toLowerCase(Locale.ROOT)).replaceFirst("");
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    /**
     * Extracts a marketplace-assigned listing id embedded in the URL path, when the URL follows a
     * recognizable "...-ID&lt;token&gt;" or trailing long-numeric-id convention. General across
     * marketplaces: not tied to any single domain's exact path structure.
     *
     * @param canonicalUrl already-canonicalized URL (host + path, no query)
     * @return listing id, when one could be extracted
     */
    private static Optional<String> listingId(String canonicalUrl) {
        Matcher idSegment = TRAILING_ID_SEGMENT.matcher(canonicalUrl);
        if (idSegment.find()) {
            return Optional.of(idSegment.group(1).toLowerCase(Locale.ROOT));
        }
        Matcher numeric = TRAILING_NUMERIC_ID.matcher(canonicalUrl);
        if (numeric.find()) {
            return Optional.of(numeric.group(1));
        }
        return Optional.empty();
    }

    private static String normalizeText(String value) {
        String noMarks = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
