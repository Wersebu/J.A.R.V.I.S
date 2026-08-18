package com.jarvis.tools.location;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.dataset.DatasetStage;
import com.jarvis.tools.dataset.GeolocationEntry;
import com.jarvis.tools.dataset.GeolocationStatus;
import com.jarvis.tools.dataset.GeolocationUpdateOutcome;
import com.jarvis.tools.dataset.StoreAuditDataset;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.dataset.StoreRecord;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

    // "point" arguments (queries/points/stops) accept either a free-text address string or a
    // {latitude,longitude} object at runtime (see resolvePoint/geocode) - the native tool-calling
    // schema model here has no anyOf, so items are declared as string (the common, documented
    // case); the object form stays usable since only the top-level array/object shape is
    // enforced at the native-tool-call boundary, not each item's shape.
    private static final ToolJsonSchema ADDRESS_ITEM_SCHEMA = ToolJsonSchema.string(
            "A free-text address/postal code/city, or an object with latitude/longitude");
    private static final ToolJsonSchema QUERIES_SCHEMA = ToolJsonSchema.arrayOf(
            ADDRESS_ITEM_SCHEMA, "A FEW free-text addresses to geocode in one batch call - for many, use storeDataset + GEOCODE_DATASET instead");
    private static final ToolJsonSchema POINTS_SCHEMA = ToolJsonSchema.arrayOf(
            ADDRESS_ITEM_SCHEMA, "List of addresses or {latitude,longitude} points");
    private static final ToolJsonSchema STOPS_SCHEMA = ToolJsonSchema.arrayOf(
            ADDRESS_ITEM_SCHEMA, "List of stop addresses or {latitude,longitude} points");
    private static final ToolJsonSchema RECORD_IDS_SCHEMA = ToolJsonSchema.arrayOf(
            ToolJsonSchema.string("An existing canonical storeDataset record id, e.g. \"store-013\""),
            "Optional: only geocode these existing canonical record ids (e.g. to retry ones that came back "
                    + "unresolved/ambiguous). Omit to geocode every record in the dataset. Ids not present in the "
                    + "dataset are reported, never used to create or match anything.");

    private final GeocodingClient geocodingClient;
    private final RoutingClient routingClient;
    private final LocationProperties properties;
    private final RouteOptimizer routeOptimizer;
    private final StoreAuditDatasetService datasetService;

    /**
     * Creates the location tool.
     *
     * @param geocodingClient geocoding provider
     * @param routingClient routing provider
     * @param properties location configuration
     * @param datasetService canonical store dataset, for {@code GEOCODE_DATASET}
     */
    public LocationTool(GeocodingClient geocodingClient, RoutingClient routingClient, LocationProperties properties,
            StoreAuditDatasetService datasetService) {
        this.geocodingClient = geocodingClient;
        this.routingClient = routingClient;
        this.properties = properties;
        this.routeOptimizer = new RouteOptimizer();
        this.datasetService = datasetService;
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
                                + "single \"query\" or a \"queries\" array to batch-resolve many addresses in one call. "
                                + "For more than a handful of addresses (e.g. many rows extracted from an image, document, "
                                + "or list) do NOT pass them here directly - first call storeDataset.CREATE_DATASET with "
                                + "the full extracted list, then use GEOCODE_DATASET on that locked dataset instead. GEOCODE "
                                + "with a raw \"queries\" batch never checks record count or provenance, so an extraction "
                                + "geocoded this way can silently drift in size with nothing catching it; GEOCODE_DATASET "
                                + "cannot. Never used for finding products, offers, or prices - use web.SEARCH_MARKETPLACE "
                                + "for that instead. Results are validated against every address detail in the query "
                                + "(postal code especially) - a name match alone is never enough. When a query can't be "
                                + "confidently pinned to one location (e.g. same place name in multiple regions, no "
                                + "matching postal code among the candidates), it comes back under \"ambiguousPoints\" with "
                                + "its candidates instead of a guessed coordinate - ask the user to clarify rather than "
                                + "using an ambiguous result as-is.",
                        false, ToolSafetyLevel.READ,
                        arg("query", "string", false, "A single free-text address/postal code/city to geocode"),
                        arg("queries", false, QUERIES_SCHEMA)),
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
                        arg("points", true, POINTS_SCHEMA)),
                operation("OPTIMIZE_ROUTE",
                        "Given a starting point and a list of stop addresses/points, proposes a visiting order that "
                                + "reasonably minimizes total travel distance or time, using real road-network data. "
                                + "This is the right operation for \"group these addresses\", \"best order to visit "
                                + "these places\", \"plan a route through these stops\" style requests.",
                        false, ToolSafetyLevel.READ,
                        arg("start", "string", true, "Starting address or {latitude,longitude}"),
                        arg("stops", true, STOPS_SCHEMA),
                        arg("optimize", "string", false, "\"distance\" or \"time\" (default \"time\")")),
                operation("GEOCODE_DATASET",
                        "Batch-geocodes records already held in a storeDataset (see the storeDataset tool) and "
                                + "updates them in place by record id - use this instead of GEOCODE whenever the "
                                + "addresses belong a storeDataset (e.g. a Store Audit dataset). Core reads each "
                                + "record's canonical id and address directly from the locked dataset itself, "
                                + "geocodes it, and writes the result back to that exact record. You never resend "
                                + "record ids or addresses here, so a typo or drift in a restated id (e.g. \"013\" "
                                + "instead of the real \"store-013\") can never cause a record to be silently "
                                + "skipped. Requires the dataset to already be VERIFIED (stage=LOCKED) - call "
                                + "storeDataset.VERIFY_DATASET first. This can never create a new store record.",
                        true, ToolSafetyLevel.WRITE,
                        arg("datasetId", "string", false, "Dataset id. Usually not needed - while a Store Audit "
                                + "workflow is active, Core automatically targets the active canonical dataset even "
                                + "if this is omitted. Only supply this explicitly for standalone/administrative use "
                                + "outside an active workflow."),
                        arg("recordIds", false, RECORD_IDS_SCHEMA))
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
            case GEOCODE_DATASET -> geocodeDataset(request);
        };
    }

    private ToolResult geocodeDataset(ToolRequest request) {
        String datasetId = arg(request, "datasetId");
        if (datasetId.isBlank()) {
            return failure(request, "GEOCODE_DATASET", "GEOCODE_DATASET_MISSING_ID", "Nie podano datasetId.");
        }
        Optional<StoreAuditDataset> maybeDataset = datasetService.getDataset(datasetId);
        if (maybeDataset.isEmpty()) {
            return failure(request, "GEOCODE_DATASET", "STORE_DATASET_NOT_FOUND", "Unknown or expired dataset id: " + datasetId);
        }
        StoreAuditDataset dataset = maybeDataset.get();
        if (dataset.stage() != DatasetStage.LOCKED && dataset.stage() != DatasetStage.GEOLOCATED) {
            String message = "Dataset " + datasetId + " has not been verified yet (stage=" + dataset.stage() + "). "
                    + "Call storeDataset.VERIFY_DATASET before geolocation.";
            return failure(request, "GEOCODE_DATASET", "STORE_DATASET_NOT_VERIFIED", message);
        }

        // Canonical, Core-sourced records only - the model can request a subset by id (e.g. to
        // retry unresolved ones) but can never resend/override a record's id or address here. This
        // is what stops a restated id like "013" (instead of the real "store-013") from silently
        // skipping a record, and stops an address drifting between what was extracted and what is
        // actually geocoded.
        List<String> requestedIds = stringListArg(request, "recordIds");
        Map<String, StoreRecord> byId = new LinkedHashMap<>();
        for (StoreRecord record : dataset.stores()) {
            byId.put(record.id(), record);
        }
        List<StoreRecord> targets;
        List<String> unknownRequestedIds = new ArrayList<>();
        if (requestedIds.isEmpty()) {
            targets = dataset.stores();
        } else {
            targets = new ArrayList<>();
            for (String id : requestedIds) {
                StoreRecord record = byId.get(id);
                if (record == null) {
                    unknownRequestedIds.add(id);
                } else {
                    targets.add(record);
                }
            }
        }
        if (targets.isEmpty()) {
            return failure(request, "GEOCODE_DATASET", "GEOCODE_DATASET_NO_RECORDS",
                    "No matching records to geocode." + (unknownRequestedIds.isEmpty() ? "" : " Unknown record id(s): " + unknownRequestedIds + "."));
        }

        List<Map<String, Object>> resultsForModel = new ArrayList<>();
        List<GeolocationEntry> updates = new ArrayList<>();
        for (StoreRecord record : targets) {
            GeocodeResult result = safeGeocode(record.fullAddress());
            GeolocationStatus status = switch (result.status()) {
                case RESOLVED -> GeolocationStatus.RESOLVED;
                case AMBIGUOUS, NOT_CONFIDENTLY_RESOLVED -> GeolocationStatus.AMBIGUOUS;
                case NOT_FOUND -> GeolocationStatus.FAILED;
            };
            Double latitude = status == GeolocationStatus.RESOLVED ? result.latitude() : null;
            Double longitude = status == GeolocationStatus.RESOLVED ? result.longitude() : null;
            updates.add(new GeolocationEntry(record.id(), status, latitude, longitude));

            Map<String, Object> entry = new HashMap<>();
            entry.put("recordId", record.id());
            entry.put("fullAddress", record.fullAddress());
            entry.put("status", status.name());
            if (latitude != null) {
                entry.put("latitude", latitude);
            }
            if (longitude != null) {
                entry.put("longitude", longitude);
            }
            if (status != GeolocationStatus.RESOLVED) {
                entry.put("reason", result.failureReason());
            }
            if (!result.candidates().isEmpty()) {
                entry.put("candidates", result.candidates().stream().map(this::candidateMap).toList());
            }
            resultsForModel.add(entry);
        }
        GeolocationUpdateOutcome outcome = datasetService.updateGeolocation(datasetId, updates);
        if (!outcome.success()) {
            String errorCode = outcome.errorCode().isBlank() ? "STORE_DATASET_NOT_FOUND" : outcome.errorCode();
            return failure(request, "GEOCODE_DATASET", errorCode, outcome.message());
        }
        LOGGER.info("[STORE_AUDIT] requestId={} GEOCODE_DATASET datasetId={} attempted={} updated={}",
                request.requestId(), datasetId, updates.size(), outcome.updatedCount());
        Map<String, Object> data = new HashMap<>();
        data.put("datasetId", datasetId);
        data.put("results", resultsForModel);
        data.put("updatedCount", outcome.updatedCount());
        data.put("datasetStage", outcome.dataset().stage().name());
        data.put("datasetCount", outcome.dataset().stores().size());
        if (!unknownRequestedIds.isEmpty()) {
            data.put("unknownRequestedRecordIds", unknownRequestedIds);
        }
        return new ToolResult(true, TOOL_NAME, "GEOCODE_DATASET", request.requestId(), request.conversationId(),
                true, List.of(datasetId), outcome.message(), data, "", "", false, "");
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
        List<Map<String, Object>> ambiguous = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (String query : queries) {
            GeocodeResult result = safeGeocode(query);
            switch (result.status()) {
                case RESOLVED -> successful.add(Map.of("query", result.query(), "latitude", result.latitude(),
                        "longitude", result.longitude(), "displayName", result.displayName()));
                case AMBIGUOUS, NOT_CONFIDENTLY_RESOLVED -> ambiguous.add(Map.of(
                        "query", result.query(), "reason", result.failureReason(),
                        "candidates", result.candidates().stream().map(this::candidateMap).toList()));
                case NOT_FOUND -> failed.add(Map.of("query", result.query(), "reason", result.failureReason()));
            }
        }
        LOGGER.info("[LOCATION] Geocoding: {}/{} punktow OK, {} niejednoznacznych requestId={}",
                successful.size(), queries.size(), ambiguous.size(), request.requestId());
        boolean anyActionable = !successful.isEmpty() || !ambiguous.isEmpty();
        Map<String, Object> data = new HashMap<>();
        data.put("successfulPoints", successful);
        data.put("failedPoints", failed);
        if (!ambiguous.isEmpty()) {
            data.put("ambiguousPoints", ambiguous);
        }
        data.put("requestedCount", queries.size());
        data.put("resolvedCount", successful.size());
        if (!warnings.isEmpty()) {
            data.put("warnings", warnings);
        }
        String message = messageFor(successful.size(), ambiguous.size(), queries.size());
        return new ToolResult(anyActionable, TOOL_NAME, "GEOCODE", request.requestId(), request.conversationId(), false, List.of(),
                message, data, anyActionable ? "" : "GEOCODING_FAILED",
                anyActionable ? "" : "Nie udalo sie jednoznacznie zlokalizowac podanych adresow.", false, "");
    }

    private String messageFor(int successCount, int ambiguousCount, int totalCount) {
        if (successCount > 0) {
            return "Zgeokodowano " + successCount + " z " + totalCount + " adresow."
                    + (ambiguousCount > 0 ? " " + ambiguousCount + " wymaga doprecyzowania (niejednoznaczna lokalizacja)." : "");
        }
        if (ambiguousCount > 0) {
            return ambiguousCount + " z " + totalCount + " adresow wymaga doprecyzowania - lokalizacja niejednoznaczna, "
                    + "potrzebne dodatkowe informacje (np. wojewodztwo, ulica) od uzytkownika.";
        }
        return "Nie udalo sie jednoznacznie zlokalizowac zadnego z podanych adresow.";
    }

    private Map<String, Object> candidateMap(GeocodeCandidate candidate) {
        Map<String, Object> map = new HashMap<>();
        map.put("latitude", candidate.latitude());
        map.put("longitude", candidate.longitude());
        map.put("displayName", candidate.displayName());
        if (!candidate.postalCode().isBlank()) {
            map.put("postalCode", candidate.postalCode());
        }
        if (!candidate.region().isBlank()) {
            map.put("region", candidate.region());
        }
        return map;
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

    private List<String> stringListArg(ToolRequest request, String name) {
        List<String> result = new ArrayList<>();
        for (Object item : listArg(request, name)) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).strip());
            }
        }
        return result;
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

    private ToolArgumentDefinition arg(String name, boolean required, ToolJsonSchema schema) {
        return new ToolArgumentDefinition(name, required, schema);
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
