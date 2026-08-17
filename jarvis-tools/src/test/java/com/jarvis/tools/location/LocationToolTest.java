package com.jarvis.tools.location;

import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link LocationTool} against hand-written fake {@link GeocodingClient}/
 * {@link RoutingClient} implementations - no HTTP, no mocking framework, matching the pattern
 * already used for {@code WebSearchToolTest}.
 */
class LocationToolTest {

    private static final LocationProperties PROPERTIES = new LocationProperties(
            true, "https://nominatim.example", "https://osrm.example", "Test-Agent/1.0",
            0, 25, 8, Duration.ofSeconds(1), Duration.ofSeconds(1), 5);

    @Test
    void geocodeBatchReturnsPartialSuccessWhenOneAddressFails() {
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Biedronka, Korczaka 7, 08-400 Garwolin", GeocodeResult.resolved(
                "Biedronka, Korczaka 7, 08-400 Garwolin", 51.90, 21.63, "Biedronka, Korczaka 7, Garwolin"));
        geocodingClient.on("Nieistniejacy Adres XYZ", GeocodeResult.unresolved("Nieistniejacy Adres XYZ", "No matching location found"));
        LocationTool tool = new LocationTool(geocodingClient, new FakeRoutingClient(), PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "GEOCODE", "conversation-1", "request-1", "", "",
                Map.of("queries", List.of("Biedronka, Korczaka 7, 08-400 Garwolin", "Nieistniejacy Adres XYZ"))));

        assertThat(result.success()).isTrue();
        assertThat((List<?>) result.data().get("successfulPoints")).hasSize(1);
        assertThat((List<?>) result.data().get("failedPoints")).hasSize(1);
        assertThat(result.data().get("requestedCount")).isEqualTo(2);
        assertThat(result.data().get("resolvedCount")).isEqualTo(1);
    }

    @Test
    void batchGeocodeRoutesAnAmbiguousAddressToItsOwnBucketNotSuccessfulOrFailed() {
        // TEST 6: batch geocoding must run every address through the same candidate validation -
        // an ambiguous result must never be silently promoted to "successful" nor lumped in with
        // genuine "not found" failures.
        GeocodeCandidate podlaskie = new GeocodeCandidate(53.0, 23.6, "Nowa Wola, podlaskie", "", "Nowa Wola", "", "", "podlaskie", "Polska");
        GeocodeCandidate mazowieckie = new GeocodeCandidate(51.75, 21.65, "Nowa Wola, mazowieckie", "", "Nowa Wola", "", "", "mazowieckie", "Polska");
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Biedronka, Korczaka 7, 08-400 Garwolin", GeocodeResult.resolved(
                "Biedronka, Korczaka 7, 08-400 Garwolin", 51.90, 21.63, "Biedronka, Korczaka 7, Garwolin"));
        geocodingClient.on("Nowa Wola", GeocodeResult.ambiguous("Nowa Wola", List.of(podlaskie, mazowieckie)));
        LocationTool tool = new LocationTool(geocodingClient, new FakeRoutingClient(), PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "GEOCODE", "conversation-1", "request-1", "", "",
                Map.of("queries", List.of("Biedronka, Korczaka 7, 08-400 Garwolin", "Nowa Wola"))));

        assertThat(result.success()).isTrue();
        assertThat((List<?>) result.data().get("successfulPoints")).hasSize(1);
        assertThat((List<?>) result.data().get("failedPoints")).isEmpty();
        List<?> ambiguousPoints = (List<?>) result.data().get("ambiguousPoints");
        assertThat(ambiguousPoints).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> ambiguousEntry = (Map<String, Object>) ambiguousPoints.get(0);
        assertThat(ambiguousEntry.get("query")).isEqualTo("Nowa Wola");
        assertThat((List<?>) ambiguousEntry.get("candidates")).hasSize(2);
    }

    @Test
    void geocodeSingleQueryStillWorksViaQueryArgument() {
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Nowa Wola 05-500", GeocodeResult.resolved("Nowa Wola 05-500", 52.0, 20.9, "Nowa Wola"));
        LocationTool tool = new LocationTool(geocodingClient, new FakeRoutingClient(), PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "GEOCODE", "conversation-1", "request-1", "", "",
                Map.of("query", "Nowa Wola 05-500")));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("resolvedCount")).isEqualTo(1);
        assertThat(result.errorMessage()).isEmpty();
    }

    @Test
    void geocodeWithNoResolvedAddressReturnsGeographicErrorNeverMarketplace() {
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Zupelnie Nieznany Adres", GeocodeResult.unresolved("Zupelnie Nieznany Adres", "No matching location found"));
        LocationTool tool = new LocationTool(geocodingClient, new FakeRoutingClient(), PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "GEOCODE", "conversation-1", "request-1", "", "",
                Map.of("query", "Zupelnie Nieznany Adres")));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("GEOCODING_FAILED");
        assertThat(result.errorMessage()).contains("zlokalizowac");
        assertThat(result.errorMessage()).doesNotContain("ofert");
        assertThat(result.data()).doesNotContainKey("marketplaceResearch");
    }

    @Test
    void routeAutoGeocodesFreeTextEndpoints() {
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Nowa Wola 05-500", GeocodeResult.resolved("Nowa Wola 05-500", 52.0, 20.9, "Nowa Wola"));
        geocodingClient.on("Garwolin", GeocodeResult.resolved("Garwolin", 51.9, 21.6, "Garwolin"));
        FakeRoutingClient routingClient = new FakeRoutingClient();
        routingClient.routeReturns(RouteResult.resolved(45000d, 2400d));
        LocationTool tool = new LocationTool(geocodingClient, routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "ROUTE", "conversation-1", "request-1", "", "",
                Map.of("from", "Nowa Wola 05-500", "to", "Garwolin")));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("distanceMeters")).isEqualTo(45000d);
        assertThat(result.data().get("durationSeconds")).isEqualTo(2400d);
    }

    @Test
    void routeAcceptsRawLatLngPairsWithoutGeocoding() {
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        FakeRoutingClient routingClient = new FakeRoutingClient();
        routingClient.routeReturns(RouteResult.resolved(1000d, 120d));
        LocationTool tool = new LocationTool(geocodingClient, routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "ROUTE", "conversation-1", "request-1", "", "",
                Map.of("from", Map.of("latitude", 52.0, "longitude", 20.9),
                        "to", Map.of("latitude", 52.01, "longitude", 20.91))));

        assertThat(result.success()).isTrue();
        assertThat(geocodingClient.callCount()).isZero();
    }

    @Test
    void routeReturnsRoutingErrorNeverMarketplaceWhenNoRouteFound() {
        FakeRoutingClient routingClient = new FakeRoutingClient();
        routingClient.routeReturns(RouteResult.unresolved("No road route found between these points"));
        LocationTool tool = new LocationTool(new FakeGeocodingClient(), routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "ROUTE", "conversation-1", "request-1", "", "",
                Map.of("from", Map.of("latitude", 52.0, "longitude", 20.9),
                        "to", Map.of("latitude", 10.0, "longitude", 10.0))));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("ROUTING_NO_ROUTE");
        assertThat(result.errorMessage()).contains("wyznaczyc trasy");
        assertThat(result.errorMessage()).doesNotContain("ofert");
    }

    @Test
    void routeMatrixReportsUnreachableCellsWithoutFailingWholeCall() {
        FakeRoutingClient routingClient = new FakeRoutingClient();
        Double[][] distances = {{0d, 1000d, null}, {1000d, 0d, null}, {null, null, 0d}};
        Double[][] durations = {{0d, 120d, null}, {120d, 0d, null}, {null, null, 0d}};
        routingClient.tableReturns(RouteMatrixResult.resolved(distances, durations));
        LocationTool tool = new LocationTool(new FakeGeocodingClient(), routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "ROUTE_MATRIX", "conversation-1", "request-1", "", "",
                Map.of("points", List.of(
                        Map.of("latitude", 52.0, "longitude", 20.9),
                        Map.of("latitude", 52.1, "longitude", 21.0),
                        Map.of("latitude", 10.0, "longitude", 10.0)))));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("distancesMeters")).isEqualTo(distances);
    }

    @Test
    void optimizeRouteUsesResolvedMatrixAndReturnsVisitOrder() {
        FakeRoutingClient routingClient = new FakeRoutingClient();
        Double[][] durations = {
                {0d, 300d, 900d},
                {300d, 0d, 400d},
                {900d, 400d, 0d}
        };
        Double[][] distances = {
                {0d, 3000d, 9000d},
                {3000d, 0d, 4000d},
                {9000d, 4000d, 0d}
        };
        routingClient.tableReturns(RouteMatrixResult.resolved(distances, durations));
        LocationTool tool = new LocationTool(new FakeGeocodingClient(), routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "OPTIMIZE_ROUTE", "conversation-1", "request-1", "", "",
                Map.of(
                        "start", Map.of("latitude", 52.0, "longitude", 20.9, "label", "Start"),
                        "stops", List.of(
                                Map.of("latitude", 52.1, "longitude", 21.0, "label", "Sklep A"),
                                Map.of("latitude", 52.2, "longitude", 21.1, "label", "Sklep B")))));

        assertThat(result.success()).isTrue();
        List<?> visitOrder = (List<?>) result.data().get("visitOrder");
        assertThat(visitOrder).hasSize(3);
        assertThat(result.data().get("totalCost")).isEqualTo(700d); // start->A(300) + A->B(400)
        assertThat(result.data().get("optimizeBy")).isEqualTo("time");
    }

    @Test
    void optimizeRouteReportsPartialSuccessWhenOneStopFailsToGeocode() {
        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Sklep A", GeocodeResult.resolved("Sklep A", 52.1, 21.0, "Sklep A"));
        geocodingClient.on("Adres Nieznany", GeocodeResult.unresolved("Adres Nieznany", "No matching location found"));
        FakeRoutingClient routingClient = new FakeRoutingClient();
        Double[][] durations = {{0d, 300d}, {300d, 0d}};
        Double[][] distances = {{0d, 3000d}, {3000d, 0d}};
        routingClient.tableReturns(RouteMatrixResult.resolved(distances, durations));
        LocationTool tool = new LocationTool(geocodingClient, routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "OPTIMIZE_ROUTE", "conversation-1", "request-1", "", "",
                Map.of(
                        "start", Map.of("latitude", 52.0, "longitude", 20.9),
                        "stops", List.of("Sklep A", "Adres Nieznany"))));

        assertThat(result.success()).isTrue();
        assertThat((List<?>) result.data().get("unresolvedStops")).hasSize(1);
    }

    @Test
    void unsupportedOperationThrowsToolException() {
        LocationTool tool = new LocationTool(new FakeGeocodingClient(), new FakeRoutingClient(), PROPERTIES, null);

        assertThatThrownBy(() -> tool.execute(new ToolRequest("location", "TELEPORT", "conversation-1", "request-1", "", "", Map.of())))
                .isInstanceOf(ToolException.class);
    }

    // TEST C/D from the Store Audit dataset invariant suite, exercised through the actual
    // location.GEOCODE_DATASET operation: it can only update existing storeDataset records by id,
    // never create one, regardless of how many results come back.
    @Test
    void geocodeDatasetUpdatesExistingDatasetRecordsWithoutEverCreatingNewOnes() {
        com.jarvis.tools.dataset.StoreAuditDatasetService datasetService =
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus());
        com.jarvis.tools.dataset.CreateOutcome created = datasetService.createDataset("request-1", 1, List.of("att-1"), List.of(
                new com.jarvis.tools.dataset.CandidateRecord("Biedronka", "Garwolin", "Korczaka", "7", "08-400",
                        "Biedronka, Korczaka 7, 08-400 Garwolin", "att-1", 1),
                new com.jarvis.tools.dataset.CandidateRecord("Biedronka", "Garwolin", "Targowa", "1", "08-400",
                        "Nieistniejacy Adres XYZ", "att-1", 2)
        ));
        String datasetId = created.dataset().datasetId();
        String recordId1 = created.dataset().stores().get(0).id();
        String recordId2 = created.dataset().stores().get(1).id();

        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Biedronka, Korczaka 7, 08-400 Garwolin", GeocodeResult.resolved(
                "Biedronka, Korczaka 7, 08-400 Garwolin", 51.90, 21.63, "Biedronka, Korczaka 7, Garwolin"));
        geocodingClient.on("Nieistniejacy Adres XYZ", GeocodeResult.unresolved("Nieistniejacy Adres XYZ", "No matching location found"));
        LocationTool tool = new LocationTool(geocodingClient, new FakeRoutingClient(), PROPERTIES, datasetService);

        ToolResult result = tool.execute(new ToolRequest("location", "GEOCODE_DATASET", "conversation-1", "request-1", "", "",
                Map.of("datasetId", datasetId, "records", List.of(
                        Map.of("recordId", recordId1, "fullAddress", "Biedronka, Korczaka 7, 08-400 Garwolin"),
                        Map.of("recordId", recordId2, "fullAddress", "Nieistniejacy Adres XYZ")
                ))));

        assertThat(result.success()).isTrue();
        assertThat(result.data().get("updatedCount")).isEqualTo(2);
        assertThat(result.data().get("datasetCount")).isEqualTo(2);

        var dataset = datasetService.getDataset(datasetId).orElseThrow();
        assertThat(dataset.stores()).hasSize(2);
        assertThat(dataset.stores().get(0).geolocationStatus()).isEqualTo(com.jarvis.tools.dataset.GeolocationStatus.RESOLVED);
        assertThat(dataset.stores().get(1).geolocationStatus()).isEqualTo(com.jarvis.tools.dataset.GeolocationStatus.FAILED);
    }

    @Test
    void geocodeDatasetIgnoresAnUnknownRecordIdRatherThanCreatingANewRecord() {
        com.jarvis.tools.dataset.StoreAuditDatasetService datasetService =
                new com.jarvis.tools.dataset.StoreAuditDatasetService(new NoopCognitiveEventBus());
        com.jarvis.tools.dataset.CreateOutcome created = datasetService.createDataset("request-1", 1, List.of("att-1"), List.of(
                new com.jarvis.tools.dataset.CandidateRecord("Biedronka", "Garwolin", "Korczaka", "7", "08-400",
                        "Biedronka, Korczaka 7, 08-400 Garwolin", "att-1", 1)
        ));
        String datasetId = created.dataset().datasetId();

        FakeGeocodingClient geocodingClient = new FakeGeocodingClient();
        geocodingClient.on("Sklep spoza datasetu", GeocodeResult.resolved("Sklep spoza datasetu", 50.0, 20.0, "Sklep spoza datasetu"));
        LocationTool tool = new LocationTool(geocodingClient, new FakeRoutingClient(), PROPERTIES, datasetService);

        ToolResult result = tool.execute(new ToolRequest("location", "GEOCODE_DATASET", "conversation-1", "request-1", "", "",
                Map.of("datasetId", datasetId, "records", List.of(
                        Map.of("recordId", "store-999", "fullAddress", "Sklep spoza datasetu")
                ))));

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> unknownIds = (List<String>) result.data().get("unknownRecordIds");
        assertThat(unknownIds).containsExactly("store-999");
        assertThat(datasetService.getDataset(datasetId).orElseThrow().stores()).hasSize(1);
    }

    private static final class NoopCognitiveEventBus implements com.jarvis.common.event.CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<com.jarvis.common.event.CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(com.jarvis.common.ai.BrainType brain, String model) {
        }

        @Override
        public void publish(com.jarvis.common.event.CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }

    @Test
    void distanceMatrixAliasIsAcceptedAsRouteMatrix() {
        FakeRoutingClient routingClient = new FakeRoutingClient();
        Double[][] distances = {{0d, 1000d}, {1000d, 0d}};
        Double[][] durations = {{0d, 120d}, {120d, 0d}};
        routingClient.tableReturns(RouteMatrixResult.resolved(distances, durations));
        LocationTool tool = new LocationTool(new FakeGeocodingClient(), routingClient, PROPERTIES, null);

        ToolResult result = tool.execute(new ToolRequest("location", "DISTANCE_MATRIX", "conversation-1", "request-1", "", "",
                Map.of("points", List.of(
                        Map.of("latitude", 52.0, "longitude", 20.9),
                        Map.of("latitude", 52.1, "longitude", 21.0)))));

        assertThat(result.success()).isTrue();
        assertThat(result.operation()).isEqualTo("ROUTE_MATRIX");
    }

    private static final class FakeGeocodingClient implements GeocodingClient {

        private final Map<String, GeocodeResult> results = new HashMap<>();
        private int callCount;

        void on(String query, GeocodeResult result) {
            results.put(query, result);
        }

        int callCount() {
            return callCount;
        }

        @Override
        public GeocodeResult geocode(String query) {
            callCount++;
            GeocodeResult result = results.get(query);
            if (result == null) {
                throw new AssertionError("Unexpected geocode query with no fixture: " + query);
            }
            return result;
        }
    }

    private static final class FakeRoutingClient implements RoutingClient {

        private RouteResult routeResult;
        private RouteMatrixResult matrixResult;

        void routeReturns(RouteResult result) {
            this.routeResult = result;
        }

        void tableReturns(RouteMatrixResult result) {
            this.matrixResult = result;
        }

        @Override
        public RouteResult route(GeoPoint from, GeoPoint to) {
            if (routeResult == null) {
                throw new AssertionError("No scripted route result");
            }
            return routeResult;
        }

        @Override
        public RouteMatrixResult table(List<GeoPoint> points) {
            if (matrixResult == null) {
                throw new AssertionError("No scripted matrix result");
            }
            return matrixResult;
        }
    }
}
