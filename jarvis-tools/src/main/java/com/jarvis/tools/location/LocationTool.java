package com.jarvis.tools.location;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Native tool for geocoding, routing, and route optimization - resolves free-text addresses to
 * coordinates and computes real road-network distances/durations/visiting orders, so the model
 * never has to misuse {@code web} search for geographic lookups. See {@link LocationToolOperation}
 * for the four exposed operations, {@link GeocodingClient}/{@link RoutingClient} for the
 * swappable provider abstraction, and {@link RouteOptimizer} for the local (no network I/O)
 * visiting-order optimization.
 */
@Service
public class LocationTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocationTool.class);
    private static final String TOOL_NAME = "location";

    private final GeocodingClient geocodingClient;
    private final RoutingClient routingClient;
    private final LocationProperties properties;
    private final RouteOptimizer routeOptimizer;

    /**
     * Creates the location tool.
     *
     * @param geocodingClient geocoding provider
     * @param routingClient routing provider
     * @param properties location configuration
     */
    public LocationTool(GeocodingClient geocodingClient, RoutingClient routingClient, LocationProperties properties) {
        this.geocodingClient = geocodingClient;
        this.routingClient = routingClient;
        this.properties = properties;
        this.routeOptimizer = new RouteOptimizer();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Geocodes addresses to coordinates and computes real road-network distances, "
                + "durations, and visiting orders between points.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                operation("GEOCODE",
                        "Resolves one or more free-text addresses, postal codes, or city names to geographic "
                                + "coordinates (latitude/longitude). Use this for ANY task that needs coordinates or "
                                + "needs to confirm a location exists - never use web search for this. Accepts either a "
                                + "single \"query\" or a \"queries\" array to batch-resolve many addresses in one call "
                                + "(preferred over calling this repeatedly for each address). Never used for finding "
                                + "products, offers, or prices - use web.SEARCH_MARKETPLACE for that instead.",
                        false, ToolSafetyLevel.READ,
                        arg("query", "string", false, "A single free-text address/postal code/city to geocode"),
                        arg("queries", "array", false, "Multiple free-text addresses to geocode in one batch call")),
                operation("ROUTE",
                        "Computes the real road-network distance and driving duration between two points - never "
                                + "a straight-line estimate. Each of \"from\"/\"to\" accepts either a free-text address "
                                + "string (auto-geocoded) or an object with \"latitude\"/\"longitude\".",
                        false, ToolSafetyLevel.READ,
                        arg("from", "string", true, "Starting address or {latitude,longitude}"),
                        arg("to", "string", true, "Destination address or {latitude,longitude}")),
                operation("ROUTE_MATRIX",
                        "Computes a road-network distance and duration matrix between every pair in a list of "
                                + "points or addresses (also covers what may be called a \"distance matrix\"). "
                                + "Each entry accepts either a free-text address (auto-geocoded) or {latitude,longitude}.",
                        false, ToolSafetyLevel.READ,
                        arg("points", "array", true, "List of addresses or {latitude,longitude} points")),
                operation("OPTIMIZE_ROUTE",
                        "Given a starting point and a list of stop addresses/points, proposes a visiting order that "
                                + "reasonably minimizes total travel distance or time, using real road-network data. "
                                + "This is the right operation for \"group these addresses\", \"best order to visit "
                                + "these places\", \"plan a route through these stops\" style requests.",
                        false, ToolSafetyLevel.READ,
                        arg("start", "string", true, "Starting address or {latitude,longitude}"),
                        arg("stops", "array", true, "List of stop addresses or {latitude,longitude} points"),
                        arg("optimize", "string", false, "\"distance\" or \"time\" (default \"time\")"))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        if (!properties.isEnabled()) {
            return failure(request, request.operation(), "LOCATION_DISABLED", "Funkcja lokalizacji jest wylaczona w konfiguracji.");
        }
        LocationToolOperation operation = operation(request);
        LOGGER.info("[LOCATION] Core wykonuje {} requestId={} conversationId={}", operation, request.requestId(), request.conversationId());
        return switch (operation) {
            case GEOCODE -> geocode(request);
            case ROUTE -> route(request);
            case ROUTE_MATRIX -> routeMatrix(request);
            case OPTIMIZE_ROUTE -> optimizeRoute(request);
        };
    }

    private ToolResult geocode(ToolRequest request) {
        List<String> queries = new ArrayList<>();
        String single = arg(request, "query");
        if (!single.isBlank()) {
            queries.add(single);
        }
        for (Object item : listArg(request, "queries")) {
            Object resolved = item instanceof Map<?, ?> map ? map.get("query") : item;
            String text = resolved == null ? "" : String.valueOf(resolved).strip();
            if (!text.isBlank()) {
                queries.add(text);
            }
        }
        if (queries.isEmpty()) {
            return failure(request, "GEOCODE", "GEOCODING_NO_QUERY", "Nie podano adresu do zgeokodowania.");
        }
        List<String> warnings = new ArrayList<>();
        boolean truncated = queries.size() > properties.maxBatchSize();
        if (truncated) {
            queries = queries.subList(0, properties.maxBatchSize());
            warnings.add("Batch truncated to " + properties.maxBatchSize() + " addresses per call");
        }
        LOGGER.info("[LOCATION] LocationTool geokoduje {} adresow requestId={}", queries.size(), request.requestId());
        List<Map<String, Object>> successful = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String query : queries) {
            GeocodeResult result = safeGeocode(query);
            if (result.resolved()) {
                successful.add(Map.of("query", result.query(), "latitude", result.latitude(),
                        "longitude", result.longitude(), "displayName", result.displayName()));
            } else {
                failed.add(Map.of("query", result.query(), "reason", result.failureReason()));
            }
        }
        LOGGER.info("[LOCATION] Geocoding: {}/{} punktow OK requestId={}", successful.size(), queries.size(), request.requestId());
        boolean anySuccess = !successful.isEmpty();
        Map<String, Object> data = new HashMap<>();
        data.put("successfulPoints", successful);
        data.put("failedPoints", failed);
        data.put("requestedCount", queries.size());
        data.put("resolvedCount", successful.size());
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        String message = anySuccess
                ? "Zgeokodowano " + successful.size() + " z " + queries.size() + " adresow."
                : "Nie udalo sie jednoznacznie zlokalizowac zadnego z podanych adresow.";
        return new ToolResult(anySuccess, TOOL_NAME, "GEOCODE", request.requestId(), request.conversationId(), false, List.of(),
                message, data, anySuccess ? "" : "GEOCODING_FAILED",
                anySuccess ? "" : "Nie udalo sie jednoznacznie zlokalizowac podanych adresow.", false, "");
    }

    private ToolResult route(ToolRequest request) {
        List<String> warnings = new ArrayList<>();
        Optional<GeoPoint> from = resolvePoint(request.arguments().get("from"), warnings);
        Optional<GeoPoint> to = resolvePoint(request.arguments().get("to"), warnings);
        if (from.isEmpty() || to.isEmpty()) {
            return failure(request, "ROUTE", "ROUTING_MISSING_POINTS",
                    "Nie udalo sie wyznaczyc trasy: nie udalo sie zlokalizowac punktu startowego lub docelowego.");
        }
        RouteResult result;
        try {
            result = routingClient.route(from.get(), to.get());
        } catch (LocationException exception) {
            return failure(request, "ROUTE", "ROUTING_PROVIDER_ERROR", "Nie udalo sie wyznaczyc trasy: " + exception.getMessage());
        }
        if (!result.resolved()) {
            return failure(request, "ROUTE", "ROUTING_NO_ROUTE", "Nie udalo sie wyznaczyc trasy: " + result.failureReason());
        }
        Map<String, Object> data = new HashMap<>();
        data.put("from", pointMap(from.get()));
        data.put("to", pointMap(to.get()));
        data.put("distanceMeters", result.distanceMeters());
        data.put("distanceKm", round(result.distanceMeters() / 1000d));
        data.put("durationSeconds", result.durationSeconds());
        data.put("durationMinutes", round(result.durationSeconds() / 60d));
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        LOGGER.info("[LOCATION] Route resolved distanceKm={} durationMin={} requestId={}",
                round(result.distanceMeters() / 1000d), round(result.durationSeconds() / 60d), request.requestId());
        return new ToolResult(true, TOOL_NAME, "ROUTE", request.requestId(), request.conversationId(), false, List.of(),
                "Trasa wyznaczona.", data, "", "", false, "");
    }

    private ToolResult routeMatrix(ToolRequest request) {
        List<Object> rawPoints = listArg(request, "points");
        if (rawPoints.isEmpty()) {
            rawPoints = listArg(request, "addresses");
        }
        if (rawPoints.size() < 2) {
            return failure(request, "ROUTE_MATRIX", "ROUTING_MATRIX_INSUFFICIENT_POINTS",
                    "Nie udalo sie wyznaczyc macierzy tras: potrzeba co najmniej dwoch punktow.");
        }
        List<String> warnings = new ArrayList<>();
        if (rawPoints.size() > properties.maxBatchSize()) {
            rawPoints = rawPoints.subList(0, properties.maxBatchSize());
            warnings.add("Point list truncated to " + properties.maxBatchSize() + " points per call");
        }
        List<GeoPoint> resolved = new ArrayList<>();
        List<Map<String, Object>> failedPoints = new ArrayList<>();
        for (Object raw : rawPoints) {
            Optional<GeoPoint> point = resolvePoint(raw, warnings);
            if (point.isPresent()) {
                resolved.add(point.get());
            } else {
                failedPoints.add(Map.of("input", String.valueOf(raw), "reason", "Could not resolve point"));
            }
        }
        if (resolved.size() < 2) {
            return failure(request, "ROUTE_MATRIX", "ROUTING_MATRIX_INSUFFICIENT_POINTS",
                    "Nie udalo sie wyznaczyc macierzy tras: zbyt malo poprawnie zlokalizowanych punktow.");
        }
        RouteMatrixResult matrixResult;
        try {
            matrixResult = routingClient.table(resolved);
        } catch (LocationException exception) {
            return failure(request, "ROUTE_MATRIX", "ROUTING_PROVIDER_ERROR", "Nie udalo sie wyznaczyc macierzy tras: " + exception.getMessage());
        }
        if (!matrixResult.resolved()) {
            return failure(request, "ROUTE_MATRIX", "ROUTING_MATRIX_FAILED", "Nie udalo sie wyznaczyc macierzy tras: " + matrixResult.failureReason());
        }
        LOGGER.info("[LOCATION] Core pobiera macierz tras dla {} punktow requestId={}", resolved.size(), request.requestId());
        Map<String, Object> data = new HashMap<>();
        data.put("points", resolved.stream().map(this::pointMap).toList());
        data.put("distancesMeters", matrixResult.distancesMeters());
        data.put("durationsSeconds", matrixResult.durationsSeconds());
        if (!failedPoints.isEmpty()) {
            data.put("failedPoints", failedPoints);
        }
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        return new ToolResult(true, TOOL_NAME, "ROUTE_MATRIX", request.requestId(), request.conversationId(), false, List.of(),
                "Macierz tras wyznaczona dla " + resolved.size() + " punktow.", data, "", "", false, "");
    }

    private ToolResult optimizeRoute(ToolRequest request) {
        List<String> warnings = new ArrayList<>();
        Optional<GeoPoint> start = resolvePoint(request.arguments().get("start"), warnings);
        if (start.isEmpty()) {
            return failure(request, "OPTIMIZE_ROUTE", "ROUTING_MISSING_START",
                    "Nie udalo sie wyznaczyc trasy: nie udalo sie zlokalizowac punktu startowego.");
        }
        List<Object> rawStops = listArg(request, "stops");
        if (rawStops.isEmpty()) {
            return failure(request, "OPTIMIZE_ROUTE", "ROUTING_MISSING_STOPS",
                    "Nie udalo sie wyznaczyc trasy: brakuje listy punktow do odwiedzenia.");
        }
        int maxStops = Math.max(1, properties.maxBatchSize() - 1);
        boolean truncated = rawStops.size() > maxStops;
        if (truncated) {
            rawStops = rawStops.subList(0, maxStops);
            warnings.add("Stop list truncated to " + maxStops + " stops per call");
        }
        List<GeoPoint> resolvedStops = new ArrayList<>();
        List<Map<String, Object>> unresolvedStops = new ArrayList<>();
        for (Object raw : rawStops) {
            Optional<GeoPoint> point = resolvePoint(raw, warnings);
            if (point.isPresent()) {
                resolvedStops.add(point.get());
            } else {
                unresolvedStops.add(Map.of("input", String.valueOf(raw), "reason", "Could not resolve point"));
            }
        }
        if (resolvedStops.isEmpty()) {
            return failure(request, "OPTIMIZE_ROUTE", "ROUTING_NO_RESOLVED_STOPS",
                    "Nie udalo sie jednoznacznie zlokalizowac zadnego z podanych punktow.");
        }
        List<GeoPoint> allPoints = new ArrayList<>();
        allPoints.add(start.get());
        allPoints.addAll(resolvedStops);

        RouteMatrixResult matrixResult;
        try {
            matrixResult = routingClient.table(allPoints);
        } catch (LocationException exception) {
            return failure(request, "OPTIMIZE_ROUTE", "ROUTING_PROVIDER_ERROR", "Nie udalo sie wyznaczyc trasy: " + exception.getMessage());
        }
        if (!matrixResult.resolved()) {
            return failure(request, "OPTIMIZE_ROUTE", "ROUTING_MATRIX_FAILED", "Nie udalo sie wyznaczyc trasy: " + matrixResult.failureReason());
        }

        String criterion = arg(request, "optimize");
        boolean byDistance = "distance".equalsIgnoreCase(criterion);
        Double[][] costMatrix = byDistance ? matrixResult.distancesMeters() : matrixResult.durationsSeconds();

        LOGGER.info("[LOCATION] Core optymalizuje kolejnosc {} punktow requestId={}", allPoints.size(), request.requestId());
        OptimizedRoute optimized = routeOptimizer.optimize(costMatrix, 0, properties.exactOptimizationMaxStops());
        LOGGER.info("[LOCATION] Route optimization finished requestId={} totalCost={}", request.requestId(), optimized.totalCost());

        List<Map<String, Object>> orderedStops = new ArrayList<>();
        List<Map<String, Object>> legs = new ArrayList<>();
        List<Integer> visitOrder = optimized.visitOrder();
        for (int position = 0; position < visitOrder.size(); position++) {
            int pointIndex = visitOrder.get(position);
            GeoPoint point = allPoints.get(pointIndex);
            orderedStops.add(pointMap(point));
            if (position > 0) {
                int previousIndex = visitOrder.get(position - 1);
                Double distance = matrixResult.distancesMeters()[previousIndex][pointIndex];
                Double duration = matrixResult.durationsSeconds()[previousIndex][pointIndex];
                legs.add(Map.of(
                        "from", pointMap(allPoints.get(previousIndex)),
                        "to", pointMap(point),
                        "distanceMeters", distance == null ? 0d : distance,
                        "durationSeconds", duration == null ? 0d : duration
                ));
            }
        }
        List<Map<String, Object>> allUnresolved = new ArrayList<>(unresolvedStops);
        for (int unreachableIndex : optimized.unreachableIndices()) {
            allUnresolved.add(pointMap(allPoints.get(unreachableIndex)));
        }

        Map<String, Object> data = new HashMap<>();
        data.put("visitOrder", orderedStops);
        data.put("legs", legs);
        data.put("totalCost", optimized.totalCost());
        data.put("optimizeBy", byDistance ? "distance" : "time");
        if (!allUnresolved.isEmpty()) {
            data.put("unresolvedStops", allUnresolved);
        }
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        String message = "Zaproponowana kolejnosc odwiedzin " + resolvedStops.size() + " punktow.";
        return new ToolResult(true, TOOL_NAME, "OPTIMIZE_ROUTE", request.requestId(), request.conversationId(), false, List.of(),
                message, data, "", "", false, "");
    }

    // ---------------------------------------------------------------------
    // Argument resolution helpers
    // ---------------------------------------------------------------------

    private Optional<GeoPoint> resolvePoint(Object value, List<String> warnings) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Map<?, ?> map) {
            Object lat = map.get("latitude");
            Object lng = map.get("longitude");
            if (lat instanceof Number latNumber && lng instanceof Number lngNumber) {
                Object label = map.containsKey("label") ? map.get("label") : map.get("query");
                return Optional.of(new GeoPoint(latNumber.doubleValue(), lngNumber.doubleValue(),
                        label == null ? "" : String.valueOf(label)));
            }
            Object query = map.containsKey("query") ? map.get("query") : map.get("address");
            return query == null ? Optional.empty() : geocodeToPoint(String.valueOf(query), warnings);
        }
        if (value instanceof String text && !text.isBlank()) {
            return geocodeToPoint(text, warnings);
        }
        return Optional.empty();
    }

    private Optional<GeoPoint> geocodeToPoint(String query, List<String> warnings) {
        GeocodeResult result = safeGeocode(query);
        if (!result.resolved()) {
            warnings.add("Could not locate \"" + query + "\": " + result.failureReason());
            return Optional.empty();
        }
        return Optional.of(result.toGeoPoint());
    }

    private GeocodeResult safeGeocode(String query) {
        try {
            return geocodingClient.geocode(query);
        } catch (LocationException exception) {
            LOGGER.warn("[LOCATION] Geocoding provider failure query=\"{}\" error={}", query, exception.getMessage());
            return GeocodeResult.unresolved(query, "Geocoding provider error: " + exception.getMessage());
        }
    }

    private Map<String, Object> pointMap(GeoPoint point) {
        return Map.of("label", point.label(), "latitude", point.latitude(), "longitude", point.longitude());
    }

    private double round(double value) {
        return Math.round(value * 100d) / 100d;
    }

    private String arg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private List<Object> listArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private ToolResult failure(ToolRequest request, String operation, String errorCode, String errorMessage) {
        return new ToolResult(false, TOOL_NAME, operation, request.requestId(), request.conversationId(), false, List.of(),
                errorMessage, Map.of(), errorCode, errorMessage, false, "");
    }

    // ---------------------------------------------------------------------
    // Schema helpers
    // ---------------------------------------------------------------------

    private ToolOperationDefinition operation(String name, String description, boolean write,
            ToolSafetyLevel safetyLevel, ToolArgumentDefinition... arguments) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safetyLevel);
    }

    private ToolArgumentDefinition arg(String name, String type, boolean required, String description) {
        return new ToolArgumentDefinition(name, type, required, description);
    }

    private LocationToolOperation operation(ToolRequest request) {
        if (request == null) {
            throw new ToolException("Tool request is required");
        }
        String raw = request.operation() == null ? "" : request.operation().trim().toUpperCase(Locale.ROOT);
        String canonical = "DISTANCE_MATRIX".equals(raw) ? "ROUTE_MATRIX" : raw;
        try {
            return LocationToolOperation.valueOf(canonical);
        } catch (IllegalArgumentException exception) {
            throw new ToolException("Unsupported location operation: " + request.operation(), exception);
        }
    }
}
