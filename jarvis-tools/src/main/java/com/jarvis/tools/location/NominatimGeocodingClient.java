package com.jarvis.tools.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenStreetMap Nominatim implementation of {@link GeocodingClient}.
 *
 * <p>Nominatim's public-instance usage policy requires a descriptive User-Agent identifying the
 * calling application (see {@link LocationProperties#userAgent()}), caps request rate at roughly
 * one request per second, and forbids unattended bulk geocoding. This class enforces the rate cap
 * itself via a simple synchronized last-call timestamp - callers issuing a batch of geocode calls
 * (see {@link LocationTool}) MUST call this sequentially, one address at a time, and must never be
 * changed to fire requests concurrently (e.g. via a thread pool or parallel stream) without first
 * revisiting Nominatim's usage policy.
 *
 * <p>Requests {@code addressdetails=1} and up to {@link LocationProperties#geocodeCandidateLimit()}
 * candidates in a single call, then hands them to {@link GeocodeCandidateScorer} to pick (or
 * refuse to pick) the best match against the query's own address details - never trusts the
 * provider's top result blindly (see the scorer's Javadoc for why).
 */
@Service
public class NominatimGeocodingClient implements GeocodingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(NominatimGeocodingClient.class);

    private final LocationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final GeocodeCandidateScorer candidateScorer;
    private final Object throttleLock = new Object();
    private long lastCallEpochMillis;

    /**
     * Creates the Nominatim client.
     *
     * @param properties location properties
     * @param objectMapper JSON mapper
     */
    public NominatimGeocodingClient(LocationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        this.candidateScorer = new GeocodeCandidateScorer();
    }

    @Override
    public GeocodeResult geocode(String query) {
        if (!properties.isEnabled()) {
            throw new LocationException("Location capability is disabled");
        }
        String normalized = query == null ? "" : query.strip();
        if (normalized.isBlank()) {
            return GeocodeResult.notFound(normalized, "Empty query");
        }
        throttle();
        try {
            URI uri = URI.create(properties.nominatimBaseUrl() + "/search?q=" + encode(normalized)
                    + "&format=jsonv2&addressdetails=1&limit=" + properties.geocodeCandidateLimit());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.readTimeout())
                    .header("Accept", "application/json")
                    .header("User-Agent", properties.userAgent())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500) {
                throw new LocationException("Nominatim returned HTTP " + response.statusCode(), response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                LOGGER.warn("[LOCATION] Nominatim returned HTTP {} for query=\"{}\"", response.statusCode(), normalized);
                return GeocodeResult.notFound(normalized, "Geocoding provider returned HTTP " + response.statusCode());
            }
            List<GeocodeCandidate> candidates = parseCandidates(response.body());
            return candidateScorer.select(normalized, candidates);
        } catch (IOException exception) {
            throw new LocationException("Nominatim request failed for " + properties.nominatimBaseUrl()
                    + ": " + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocationException("Nominatim request interrupted", exception);
        }
    }

    private List<GeocodeCandidate> parseCandidates(String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            if (!root.isArray()) {
                return List.of();
            }
            List<GeocodeCandidate> candidates = new ArrayList<>();
            for (JsonNode node : root) {
                GeocodeCandidate candidate = toCandidate(node);
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
            return candidates;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[LOCATION] Could not parse Nominatim response: {}", exception.getMessage());
            return List.of();
        }
    }

    private GeocodeCandidate toCandidate(JsonNode node) {
        double latitude = parseCoordinate(node.path("lat").asText(""));
        double longitude = parseCoordinate(node.path("lon").asText(""));
        if (Double.isNaN(latitude) || Double.isNaN(longitude)) {
            return null;
        }
        JsonNode address = node.path("address");
        String city = firstNonBlank(
                text(address, "city"), text(address, "town"), text(address, "village"), text(address, "municipality"));
        return new GeocodeCandidate(
                latitude,
                longitude,
                node.path("display_name").asText(""),
                text(address, "postcode"),
                city,
                text(address, "road"),
                text(address, "house_number"),
                text(address, "state"),
                text(address, "country")
        );
    }

    private double parseCoordinate(String value) {
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private void throttle() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastCallEpochMillis;
            long waitMillis = properties.minGeocodeIntervalMillis() - elapsed;
            if (lastCallEpochMillis > 0 && waitMillis > 0) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            lastCallEpochMillis = System.currentTimeMillis();
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
