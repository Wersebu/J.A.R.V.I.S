package com.jarvis.tools.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Classifies public web URLs used by J.A.R.V.I.S. web tools.
 */
public final class WebUrlClassifier {

    private WebUrlClassifier() {
    }

    /**
     * Returns the normalized host/domain for a URL.
     *
     * @param url URL
     * @return domain without leading www
     */
    public static String domain(String url) {
        try {
            String host = new URI(url).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "").toLowerCase(Locale.ROOT);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return "";
        }
    }

    /**
     * Returns true when the URL looks like a concrete marketplace/product listing.
     *
     * @param url URL
     * @return true for concrete listing URLs
     */
    public static boolean isConcreteListing(String url) {
        try {
            URI uri = new URI(url);
            String host = host(uri);
            String path = path(uri);
            if (host.endsWith("olx.pl")) {
                return path.contains("/d/oferta/") || path.contains("/oferta/");
            }
            if (host.endsWith("allegro.pl") || host.endsWith("allegrolokalnie.pl")) {
                return path.contains("/oferta/");
            }
            if (host.endsWith("ceneo.pl")) {
                return path.matches("/\\d+.*");
            }
            if (host.endsWith("x-kom.pl")) {
                return path.startsWith("/p/");
            }
            if (host.endsWith("morele.net")) {
                return path.matches(".*/\\d+/?$") && !path.contains("wyszukiwarka");
            }
            return false;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Returns true when the URL looks like a search/category page.
     *
     * @param url URL
     * @return true for search/category pages
     */
    public static boolean isSearchPage(String url) {
        try {
            URI uri = new URI(url);
            String host = host(uri);
            String path = path(uri);
            String query = uri.getQuery() == null ? "" : uri.getQuery().toLowerCase(Locale.ROOT);
            if (host.endsWith("olx.pl")) {
                return path.contains("/q-") || query.contains("search") || query.contains("q=");
            }
            if (host.endsWith("allegro.pl") || host.endsWith("allegrolokalnie.pl")) {
                return path.startsWith("/listing") || path.startsWith("/kategoria")
                        || path.startsWith("/oferty") || query.contains("string=");
            }
            if (host.endsWith("ceneo.pl")) {
                return path.contains("szukaj") || query.contains("szukaj")
                        || query.contains("search") || query.contains("q=");
            }
            if (host.endsWith("x-kom.pl")) {
                return path.startsWith("/szukaj") || query.contains("q=");
            }
            return false;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            return false;
        }
    }

    private static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static String path(URI uri) {
        return uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
    }
}
