package com.jarvis.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP implementation for safe public web page reading.
 */
@Service
public class HttpWebPageReader implements WebPageReader {

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern JSON_LD_PATTERN = Pattern.compile(
            "(?is)<script[^>]+type\\s*=\\s*['\"]application/ld\\+json['\"][^>]*>(.*?)</script>");
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?is)\"(?:price|regularPrice|displayPrice|amount|value)\"\\s*:\\s*(?:\"([^\"]{1,80})\"|([0-9][0-9 .,\u00a0]{0,24}))");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile(
            "(?is)\"(?:priceCurrency|currency|currencyCode)\"\\s*:\\s*\"([A-Z]{3}|zł|PLN)\"");
    private static final Pattern EMBEDDED_TITLE_PATTERN = Pattern.compile(
            "(?is)\"(?:title|name)\"\\s*:\\s*\"([^\"{}]{3,180})\"");
    private static final Pattern LINK_PATTERN = Pattern.compile(
            "(?is)<a\\b[^>]*href\\s*=\\s*['\"]([^'\"]{1,2000})['\"][^>]*>(.*?)</a>");
    private static final Pattern EMBEDDED_URL_PATTERN = Pattern.compile(
            "(?is)https?:\\\\?/\\\\?/[^\"'\\s<>]{8,2000}");

    private final WebSearchProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Creates the reader.
     *
     * @param properties web search properties
     */
    public HttpWebPageReader(WebSearchProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public WebPageContent read(String url) {
        URI uri = safeUri(url);
        long started = System.nanoTime();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout())
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.1")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/126.0 Safari/537.36 Jarvis-WebSearchTool/2.5")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebSearchException("Web page returned HTTP " + response.statusCode());
            }
            String body = response.body() == null ? "" : response.body();
            String contentType = response.headers().firstValue("content-type").orElse("");
            String title = decode(extractTitle(body));
            String text = normalizeText(enrichWithStructuredData(body));
            int max = properties.pageMaxLength();
            boolean truncated = text.length() > max;
            String returned = truncated ? text.substring(0, max).strip() : text;
            List<WebPageContent.WebPageLink> links = extractLinks(body, uri);
            return new WebPageContent(uri.toString(), title, returned, returned.length(), truncated, durationMs,
                    response.statusCode(), contentType, links);
        } catch (IOException exception) {
            throw new WebSearchException("Web page request failed for " + uri + ": " + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WebSearchException("Web page request interrupted for " + uri, exception);
        }
    }

    private URI safeUri(String value) {
        try {
            URI uri = new URI(value == null ? "" : value.strip()).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new WebSearchException("Only http/https web page URLs are supported");
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new WebSearchException("Web page URL host is required");
            }
            if (!allowPrivateHosts() && isPrivateHost(uri.getHost())) {
                throw new WebSearchException("Private or local web page URLs are not allowed");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw new WebSearchException("Invalid web page URL", exception);
        }
    }

    /**
     * Allows tests to bypass private-host protection without changing production behavior.
     *
     * @return false in production
     */
    protected boolean allowPrivateHosts() {
        return false;
    }

    private boolean isPrivateHost(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress();
        } catch (IOException exception) {
            throw new WebSearchException("Web page host could not be resolved: " + host, exception);
        }
    }

    private String normalizeText(String html) {
        String text = html
                .replaceAll("(?is)<script\\b[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style\\b[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript\\b[^>]*>.*?</noscript>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<br\\s*/?>", "\n")
                .replaceAll("(?is)</(p|div|section|article|li|tr|h[1-6])>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");
        return decode(text)
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n+ *", "\n")
                .replaceAll(" {2,}", " ")
                .strip();
    }

    private List<WebPageContent.WebPageLink> extractLinks(String html, URI baseUri) {
        List<WebPageContent.WebPageLink> links = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = LINK_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find() && links.size() < 40) {
            String href = decode(matcher.group(1)).strip();
            if (href.isBlank() || href.startsWith("#") || href.toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                continue;
            }
            String absolute = resolveLink(baseUri, href);
            if (absolute.isBlank() || !seen.add(absolute)) {
                continue;
            }
            String label = normalizeText(matcher.group(2));
            if (label.length() > 180) {
                label = label.substring(0, 180).strip();
            }
            if (label.isBlank()) {
                label = absolute;
            }
            links.add(new WebPageContent.WebPageLink(label, absolute));
        }
        Matcher embedded = EMBEDDED_URL_PATTERN.matcher(html == null ? "" : html);
        while (embedded.find() && links.size() < 40) {
            String absolute = decode(embedded.group()).replace("\\/", "/").replace("\\u002F", "/").strip();
            if (!isLikelyOfferUrl(absolute) || !seen.add(absolute)) {
                continue;
            }
            links.add(new WebPageContent.WebPageLink(titleFromUrl(absolute), absolute));
        }
        return List.copyOf(links);
    }

    private boolean isLikelyOfferUrl(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            if (host.endsWith("olx.pl")) {
                return path.contains("/d/oferta/") || path.contains("/oferta/");
            }
            if (host.endsWith("allegro.pl")) {
                return path.contains("/oferta/");
            }
            return false;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private String titleFromUrl(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null || path.isBlank()) {
                return url;
            }
            String leaf = path.substring(path.lastIndexOf('/') + 1)
                    .replaceAll("-CID\\d+-.*$", "")
                    .replaceAll("\\.html$", "")
                    .replace('-', ' ')
                    .strip();
            return leaf.isBlank() ? url : leaf;
        } catch (URISyntaxException exception) {
            return url;
        }
    }

    private String resolveLink(URI baseUri, String href) {
        try {
            URI resolved = baseUri.resolve(href).normalize();
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "";
            }
            return resolved.toString();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String enrichWithStructuredData(String html) {
        String value = html == null ? "" : html;
        StringBuilder builder = new StringBuilder();
        appendLine(builder, "Page title", extractTitle(value));
        appendLine(builder, "Meta title", meta(value, "og:title").orElse(""));
        appendLine(builder, "Meta description", meta(value, "description").or(() -> meta(value, "og:description")).orElse(""));
        appendStructuredJsonLd(builder, value);
        appendEmbeddedOfferHints(builder, value);
        if (!builder.isEmpty()) {
            builder.append("\n--- Visible page text ---\n");
        }
        builder.append(value);
        return builder.toString();
    }

    private Optional<String> meta(String html, String name) {
        String escaped = Pattern.quote(name);
        Pattern pattern = Pattern.compile("(?is)<meta[^>]+(?:name|property)\\s*=\\s*['\"]" + escaped
                + "['\"][^>]+content\\s*=\\s*['\"]([^'\"]{1,1000})['\"][^>]*>");
        Matcher matcher = pattern.matcher(html == null ? "" : html);
        return matcher.find() ? Optional.of(decode(matcher.group(1)).strip()) : Optional.empty();
    }

    private void appendStructuredJsonLd(StringBuilder builder, String html) {
        Matcher matcher = JSON_LD_PATTERN.matcher(html == null ? "" : html);
        int count = 0;
        while (matcher.find() && count < 5) {
            String json = decode(matcher.group(1)).strip();
            if (json.isBlank()) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(json);
                collectJsonLdFacts(root, builder, "Structured data");
                count++;
            } catch (IOException ignored) {
                appendLine(builder, "Structured data", json.length() > 800 ? json.substring(0, 800) : json);
                count++;
            }
        }
    }

    private void collectJsonLdFacts(JsonNode node, StringBuilder builder, String prefix) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            int index = 1;
            for (JsonNode child : node) {
                collectJsonLdFacts(child, builder, prefix + " " + index++);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        appendLine(builder, prefix + " type", node.path("@type").asText(""));
        appendLine(builder, prefix + " name", node.path("name").asText(""));
        appendLine(builder, prefix + " description", node.path("description").asText(""));
        appendLine(builder, prefix + " url", node.path("url").asText(""));
        JsonNode offers = node.path("offers");
        if (!offers.isMissingNode()) {
            collectOfferFacts(offers, builder, prefix + " offer");
        }
        JsonNode graph = node.path("@graph");
        if (!graph.isMissingNode()) {
            collectJsonLdFacts(graph, builder, prefix + " graph");
        }
    }

    private void collectOfferFacts(JsonNode offers, StringBuilder builder, String prefix) {
        if (offers.isArray()) {
            int index = 1;
            for (JsonNode offer : offers) {
                collectOfferFacts(offer, builder, prefix + " " + index++);
            }
            return;
        }
        if (!offers.isObject()) {
            return;
        }
        appendLine(builder, prefix + " price", offers.path("price").asText(""));
        appendLine(builder, prefix + " currency", offers.path("priceCurrency").asText(""));
        appendLine(builder, prefix + " availability", offers.path("availability").asText(""));
        appendLine(builder, prefix + " url", offers.path("url").asText(""));
    }

    private void appendEmbeddedOfferHints(StringBuilder builder, String html) {
        String value = html == null ? "" : html;
        Set<String> titles = new LinkedHashSet<>();
        collectMatches(EMBEDDED_TITLE_PATTERN, value, titles, 4);
        Set<String> prices = new LinkedHashSet<>();
        Matcher priceMatcher = PRICE_PATTERN.matcher(value);
        while (priceMatcher.find() && prices.size() < 8) {
            String price = priceMatcher.group(1) == null ? priceMatcher.group(2) : priceMatcher.group(1);
            if (price != null && !price.isBlank()) {
                prices.add(decode(price).strip());
            }
        }
        Set<String> currencies = new LinkedHashSet<>();
        collectMatches(CURRENCY_PATTERN, value, currencies, 4);
        if (!titles.isEmpty() || !prices.isEmpty() || !currencies.isEmpty()) {
            builder.append("Embedded page data:\n");
            if (!titles.isEmpty()) {
                builder.append("Titles: ").append(String.join(" | ", titles)).append("\n");
            }
            if (!prices.isEmpty()) {
                builder.append("Prices: ").append(String.join(" | ", prices)).append("\n");
            }
            if (!currencies.isEmpty()) {
                builder.append("Currencies: ").append(String.join(" | ", currencies)).append("\n");
            }
        }
    }

    private void collectMatches(Pattern pattern, String value, Set<String> target, int limit) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find() && target.size() < limit) {
            String match = matcher.group(1);
            if (match != null && !match.isBlank()) {
                target.add(decode(match).strip());
            }
        }
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        String clean = decode(value).replaceAll("\\s+", " ").strip();
        if (!clean.isBlank()) {
            builder.append(label).append(": ").append(clean).append("\n");
        }
    }

    private String extractTitle(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html == null ? "" : html);
        return matcher.find() ? matcher.group(1).replaceAll("(?is)<[^>]+>", " ").strip() : "";
    }

    private String decode(String value) {
        return (value == null ? "" : value)
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("\\/", "/")
                .replaceAll("&#(\\d+);", " ");
    }
}
