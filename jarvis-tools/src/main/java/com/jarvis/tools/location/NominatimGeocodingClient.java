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
 */
@Service
public class NominatimGeocodingClient implements GeocodingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(NominatimGeocodingClient.class);

    private final LocationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
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
    }

    @Override
    public GeocodeResult geocode(String query) {
        if (!properties.isEnabled()) {
            throw new LocationException("Location capability is disabled");
        }
        String normalized = query == null ? "" : query.strip();
        if (normalized.isBlank()) {
            return GeocodeResult.unresolved(normalized, "Empty query");
        }
        throttle();
        try {
            URI uri = URI.create(properties.nominatimBaseUrl() + "/search?q=" + encode(normalized) + "&format=jsonv2&limit=1");
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
                return GeocodeResult.unresolved(normalized, "Geocoding provider returned HTTP " + response.statusCode());
            }
            return parse(normalized, response.body());
        } catch (IOException exception) {
            throw new LocationException("Nominatim request failed for " + properties.nominatimBaseUrl()
                    + ": " + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocationException("Nominatim request interrupted", exception);
        }
    }

    private GeocodeResult parse(String query, String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            if (!root.isArray() || root.isEmpty()) {
                return GeocodeResult.unresolved(query, "No matching location found");
            }
            JsonNode first = root.get(0);
            double lat = first.path("lat").asText("").isBlank() ? Double.NaN : Double.parseDouble(first.path("lat").asText());
            double lon = first.path("lon").asText("").isBlank() ? Double.NaN : Double.parseDouble(first.path("lon").asText());
            if (Double.isNaN(lat) || Double.isNaN(lon)) {
                return GeocodeResult.unresolved(query, "Provider response missing coordinates");
            }
            String displayName = first.path("display_name").asText(query);
            return GeocodeResult.resolved(query, lat, lon, displayName);
        } catch (IOException | RuntimeException exception) {
            return GeocodeResult.unresolved(query, "Could not parse geocoding response");
        }
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
