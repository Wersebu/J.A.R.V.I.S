package com.jarvis.tools.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * OSRM implementation of {@link RoutingClient}.
 *
 * <p><b>Coordinate order:</b> OSRM's URL format is {@code longitude,latitude} - the opposite of
 * {@link GeoPoint}'s {@code latitude, longitude} field order used everywhere else in this tool.
 * Every coordinate written into a request URL in this class is deliberately built as
 * {@code point.longitude() + "," + point.latitude()} - do not "simplify" this without checking OSRM's
 * API docs again.
 */
@Service
public class OsrmRoutingClient implements RoutingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OsrmRoutingClient.class);
    private static final String NO_ROUTE_CODE = "NoRoute";

    private final LocationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /**
     * Creates the OSRM client.
     *
     * @param properties location properties
     * @param objectMapper JSON mapper
     */
    public OsrmRoutingClient(LocationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    @Override
    public RouteResult route(GeoPoint from, GeoPoint to) {
        if (!properties.isEnabled()) {
            throw new LocationException("Location capability is disabled");
        }
        URI uri = URI.create(properties.osrmBaseUrl() + "/route/v1/driving/"
                + coordinate(from) + ";" + coordinate(to) + "?overview=false");
        String body = sendWithRetry(uri, "route");
        return parseRoute(body);
    }

    @Override
    public RouteMatrixResult table(List<GeoPoint> points) {
        if (!properties.isEnabled()) {
            throw new LocationException("Location capability is disabled");
        }
        if (points.size() < 2) {
            return RouteMatrixResult.unresolved("At least two points are required for a distance matrix");
        }
        StringBuilder coordinates = new StringBuilder();
        for (int index = 0; index < points.size(); index++) {
            if (index > 0) {
                coordinates.append(';');
            }
            coordinates.append(coordinate(points.get(index)));
        }
        URI uri = URI.create(properties.osrmBaseUrl() + "/table/v1/driving/" + coordinates + "?annotations=distance,duration");
        String body = sendWithRetry(uri, "table");
        return parseMatrix(body, points.size());
    }

    private String sendWithRetry(URI uri, String operation) {
        try {
            return send(uri);
        } catch (LocationException transientFailure) {
            LOGGER.warn("[LOCATION] OSRM {} request failed once, retrying: {}", operation, transientFailure.getMessage());
            return send(uri);
        }
    }

    private String send(URI uri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(properties.readTimeout())
                    .header("Accept", "application/json")
                    .header("User-Agent", properties.userAgent())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 500) {
                // Transient - safe to retry once. A clean 4xx/"NoRoute" is handled below, never here.
                throw new LocationException("OSRM returned HTTP " + response.statusCode(), response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new LocationException("OSRM request failed for " + properties.osrmBaseUrl()
                    + ": " + exception.getClass().getSimpleName(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LocationException("OSRM request interrupted", exception);
        }
    }

    private RouteResult parseRoute(String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            String code = root.path("code").asText("");
            if (!"Ok".equalsIgnoreCase(code)) {
                return RouteResult.unresolved(NO_ROUTE_CODE.equalsIgnoreCase(code)
                        ? "No road route found between these points" : "Routing provider reported: " + code);
            }
            JsonNode routes = root.path("routes");
            if (!routes.isArray() || routes.isEmpty()) {
                return RouteResult.unresolved("No road route found between these points");
            }
            JsonNode firstRoute = routes.get(0);
            return RouteResult.resolved(firstRoute.path("distance").asDouble(0d), firstRoute.path("duration").asDouble(0d));
        } catch (IOException | RuntimeException exception) {
            return RouteResult.unresolved("Could not parse routing response");
        }
    }

    private RouteMatrixResult parseMatrix(String body, int pointCount) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            String code = root.path("code").asText("");
            if (!"Ok".equalsIgnoreCase(code)) {
                return RouteMatrixResult.unresolved("Routing provider reported: " + code);
            }
            Double[][] distances = matrix(root.path("distances"), pointCount);
            Double[][] durations = matrix(root.path("durations"), pointCount);
            return RouteMatrixResult.resolved(distances, durations);
        } catch (IOException | RuntimeException exception) {
            return RouteMatrixResult.unresolved("Could not parse routing matrix response");
        }
    }

    private Double[][] matrix(JsonNode node, int size) {
        Double[][] matrix = new Double[size][size];
        if (!node.isArray()) {
            return matrix;
        }
        for (int row = 0; row < size && row < node.size(); row++) {
            JsonNode rowNode = node.get(row);
            if (!rowNode.isArray()) {
                continue;
            }
            for (int col = 0; col < size && col < rowNode.size(); col++) {
                JsonNode cell = rowNode.get(col);
                matrix[row][col] = cell.isNull() ? null : cell.asDouble();
            }
        }
        return matrix;
    }

    private String coordinate(GeoPoint point) {
        return String.format(Locale.ROOT, "%.6f,%.6f", point.longitude(), point.latitude());
    }
}
