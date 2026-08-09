package com.jarvis.tools.web;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP implementation for safe public web page reading.
 */
@Service
public class HttpWebPageReader implements WebPageReader {

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");

    private final WebSearchProperties properties;
    private final HttpClient httpClient;

    /**
     * Creates the reader.
     *
     * @param properties web search properties
     */
    public HttpWebPageReader(WebSearchProperties properties) {
        this.properties = properties;
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
                .header("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.1")
                .header("User-Agent", "Jarvis-WebSearchTool/2.5")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new WebSearchException("Web page returned HTTP " + response.statusCode());
            }
            String body = response.body() == null ? "" : response.body();
            String title = decode(extractTitle(body));
            String text = normalizeText(body);
            int max = properties.pageMaxLength();
            boolean truncated = text.length() > max;
            String returned = truncated ? text.substring(0, max).strip() : text;
            return new WebPageContent(uri.toString(), title, returned, returned.length(), truncated, durationMs);
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
                .replaceAll("&#(\\d+);", " ");
    }
}
