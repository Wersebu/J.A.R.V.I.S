package com.jarvis.tools.dataset;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.location.GeoPoint;
import com.jarvis.tools.location.GeocodeResult;
import com.jarvis.tools.location.GeocodingClient;
import com.jarvis.tools.location.GoogleMapsLinks;
import com.jarvis.tools.location.RouteMatrixResult;
import com.jarvis.tools.location.RouteResult;
import com.jarvis.tools.location.RoutingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planning rules of Work/Scheduling/StoreAuditScheduleWorkflow.md, applied by Core: Nowa Wola start, 4 Biedronki /
 * 7 short audits per day, a distant region visited once when it fits, a borderline region turned into a question,
 * Tuesday/Wednesday dates spread over the month, and a plan the user can change before it is saved.
 */
class AuditPlannerTest {

    /** Monday 12 October 2026. */
    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
    private StoreAuditDatasetService service;
    private StoreDatasetTool tool;
    private AuditPlanner planner;
    private String datasetId;

    /** Driving distance = straight line x 1.3, at 70 km/h. */
    private static final RoutingClient ROADS = new RoutingClient() {
        @Override
        public RouteResult route(GeoPoint from, GeoPoint to) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RouteMatrixResult table(List<GeoPoint> points) {
            int n = points.size();
            Double[][] meters = new Double[n][n];
            Double[][] seconds = new Double[n][n];
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    double km = straightKm(points.get(a), points.get(b)) * 1.3;
                    meters[a][b] = km * 1000;
                    seconds[a][b] = km / 70 * 3600;
                }
            }
            return RouteMatrixResult.resolved(meters, seconds);
        }
    };

    private static double straightKm(GeoPoint a, GeoPoint b) {
        double dLat = (a.latitude() - b.latitude()) * 111.2;
        double dLon = (a.longitude() - b.longitude()) * 111.2 * Math.cos(Math.toRadians(52));
        return Math.sqrt(dLat * dLat + dLon * dLon);
    }

    private static final GeocodingClient NOWA_WOLA = query -> GeocodeResult.resolved(query, 52.00, 21.00, "Nowa Wola");

    private static final class QuietBus implements CognitiveEventBus {
        @Override
        public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }

    private record Store(String network, String city, String address, double lat, double lon) {
    }

    /** Garwolin: 5 Biedronki ~57 km away by road, close together. Konstancin: 2 Biedronki + 3 Żabki ~15 km away. */
    private static final List<Store> STORES = List.of(
            new Store("Biedronka", "Garwolin", "Korczaka 7, 08-400 Garwolin", 51.900, 21.620),
            new Store("Biedronka", "Garwolin", "Targowa 1, 08-400 Garwolin", 51.905, 21.625),
            new Store("Biedronka", "Garwolin", "Kościuszki 14, 08-400 Garwolin", 51.895, 21.615),
            new Store("Biedronka", "Garwolin", "Lubelska 30, 08-400 Garwolin", 51.902, 21.630),
            new Store("Biedronka", "Wola Rębkowska", "Główna 2, 08-400 Wola Rębkowska", 51.910, 21.640),
            new Store("Biedronka", "Konstancin-Jeziorna", "Wojska Polskiego 3, 05-520 Konstancin-Jeziorna", 52.080, 21.120),
            new Store("Biedronka", "Konstancin-Jeziorna", "Warszawska 101, 05-520 Konstancin-Jeziorna", 52.085, 21.125),
            new Store("Żabka", "Konstancin-Jeziorna", "Mirkowska 5, 05-520 Konstancin-Jeziorna", 52.082, 21.118),
            new Store("Żabka", "Konstancin-Jeziorna", "Piaseczyńska 9, 05-520 Konstancin-Jeziorna", 52.078, 21.122),
            new Store("Żabka", "Konstancin-Jeziorna", "Sobieskiego 2, 05-520 Konstancin-Jeziorna", 52.081, 21.128));

    @BeforeEach
    void setup() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new StoreAuditDatasetService(new QuietBus(), clock);
        planner = new AuditPlanner(NOWA_WOLA, ROADS, clock, "Nowa Wola, 05-500, Polska", 105, 8,
                new AuditPlanner.PlannerLimits(4, 7), 15, 35, 40);
        tool = new StoreDatasetTool(service, planner);
        List<Map<String, Object>> records = new ArrayList<>();
        for (int i = 0; i < STORES.size(); i++) {
            Store store = STORES.get(i);
            records.add(Map.of("network", store.network(), "city", store.city(), "fullAddress", store.address(),
                    "sourceAttachmentId", "att-1", "sourceRow", i + 1));
        }
        ToolResult created = call("CREATE_DATASET", Map.of("sourceImageCount", 1, "sourceAttachmentIds", List.of("att-1"), "records", records));
        assertThat(created.success()).as(created.message()).isTrue();
        datasetId = (String) created.data().get("datasetId");
        assertThat(call("VERIFY_DATASET", Map.of("datasetId", datasetId, "verifyAll", true)).success()).isTrue();
        ToolResult preferences = call("SET_PREFERENCES", Map.of("datasetId", datasetId, "year", 2026, "month", 11,
                "preferredDaysOfWeek", List.of("TUESDAY", "WEDNESDAY"), "fallbackDaysOfWeek", List.of("MONDAY"), "strategy", "EVEN"));
        assertThat(preferences.success()).as(preferences.message()).isTrue();
        StoreAuditDataset dataset = service.getDataset(datasetId).orElseThrow();
        List<GeolocationEntry> located = new ArrayList<>();
        for (int i = 0; i < STORES.size(); i++) {
            located.add(new GeolocationEntry(dataset.stores().get(i).id(), GeolocationStatus.RESOLVED, STORES.get(i).lat(), STORES.get(i).lon()));
        }
        assertThat(service.updateGeolocation(datasetId, located).success()).isTrue();
    }

    private ToolResult call(String operation, Map<String, Object> args) {
        Map<String, Object> withDataset = new java.util.LinkedHashMap<>(args);
        // In Core the tool loop injects the active dataset id; here it is passed explicitly.
        if (datasetId != null) withDataset.putIfAbsent("datasetId", datasetId);
        return tool.execute(new ToolRequest("storeDataset", operation, "conversation-1", "request-1", "", "", withDataset));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> days(ToolResult result) {
        return (List<Map<String, Object>>) result.data().get("days");
    }

    @SuppressWarnings("unchecked")
    private static List<String> addresses(Map<String, Object> day) {
        return ((List<Map<String, Object>>) day.get("stores")).stream().map(s -> String.valueOf(s.get("address"))).toList();
    }

    @Test
    void farRegionFirstWithinLimitsAndTheBorderlineCaseBecomesAQuestion() {
        ToolResult plan = call("PLAN_SCHEDULE", Map.of());

        assertThat(plan.success()).as(plan.message()).isTrue();
        assertThat(plan.message()).contains("PRELIMINARY", "not saved", "ask each decision");
        List<Map<String, Object>> days = days(plan);
        assertThat(days).hasSize(3);
        // Every store exactly once.
        List<String> all = days.stream().flatMap(d -> addresses(d).stream()).toList();
        assertThat(all).hasSize(10).doesNotHaveDuplicates();
        // Garwolin is one region: four Biedronki on one day, never mixed with Konstancin.
        Map<String, Object> garwolin = days.stream().filter(d -> addresses(d).size() == 4 && addresses(d).getFirst().contains("08-400")).findFirst().orElseThrow();
        assertThat(addresses(garwolin)).allMatch(a -> a.contains("08-400"));
        Map<String, Object> konstancin = days.stream().filter(d -> addresses(d).size() == 5).findFirst().orElseThrow();
        assertThat(addresses(konstancin)).allMatch(a -> a.contains("05-520"));
        // The fifth Garwolin-area Biedronka would need its own ~160 km trip: Jarvis asks instead of deciding.
        List<?> decisions = (List<?>) plan.data().get("decisions");
        assertThat(decisions).hasSize(1);
        String question = String.valueOf(((Map<?, ?>) decisions.getFirst()).get("question"));
        // The store left out is the Garwolin one closest to Nowa Wola: its separate trip is the shortest.
        assertThat(question).contains("Kościuszki 14, 08-400 Garwolin", "dodatkowy wyjazd ok. 114 km", "A) dołożyć do dnia 1",
                "B) zostawić osobno", "5 Biedronek");
        // Tuesdays/Wednesdays of November, spread over the month, in calendar order.
        assertThat(days.stream().map(d -> String.valueOf(d.get("dateIso"))).toList()).containsExactly("2026-11-04", "2026-11-17", "2026-11-24");
        assertThat(days.getFirst().get("date")).isEqualTo("środa, 4 listopada 2026");
        // Closed trip from Nowa Wola and a navigation link for the day.
        String link = String.valueOf(((List<?>) garwolin.get("mapsLinks")).getFirst());
        assertThat(link).startsWith("https://www.google.com/maps/dir/?api=1").contains("origin=Nowa+Wola").contains("destination=Nowa+Wola")
                .contains("waypoints=");
        assertThat(((Number) garwolin.get("distanceKm")).longValue()).isBetween(110L, 130L);
        assertThat(garwolin.get("auditTime")).isEqualTo("7 h 0 min");
        // The plan waits for the user: the completion gate lets Jarvis stop and ask.
        assertThat(service.getDataset(datasetId).orElseThrow().pendingUserInput()).isEqualTo(WorkflowPause.AWAITING_DECISION);
        assertThat(service.getDataset(datasetId).orElseThrow().stage()).isEqualTo(DatasetStage.GEOLOCATED);
    }

    @Test
    void optionAMergesTheRegionIntoOneTrip() {
        call("PLAN_SCHEDULE", Map.of());
        ToolResult merged = call("ADJUST_PLAN", Map.of("acceptDecision", 1));

        assertThat(merged.success()).as(merged.message()).isTrue();
        List<Map<String, Object>> days = days(merged);
        assertThat(days).hasSize(2);
        assertThat(days.stream().map(d -> addresses(d).size()).toList()).containsExactlyInAnyOrder(5, 5);
        assertThat(merged.data()).doesNotContainKey("decisions");
        assertThat(days.stream().map(d -> String.valueOf(d.get("dateIso"))).toList()).containsExactly("2026-11-10", "2026-11-24");
    }

    @Test
    void theUserCanReshapeThePlanBeforeAccepting() {
        call("PLAN_SCHEDULE", Map.of());
        // Move the left-out Garwolin store (#3) into day 1 by hand.
        ToolResult moved = call("ADJUST_PLAN", Map.of("moveStoreIndex", 3, "toDay", 1));
        assertThat(moved.success()).as(moved.message()).isTrue();
        assertThat(addresses(days(moved).getFirst())).contains("Kościuszki 14, 08-400 Garwolin").hasSize(5);
        assertThat(days(moved)).hasSize(2);
        assertThat(moved.data()).doesNotContainKey("decisions");

        // A Thursday is not one of the agreed days.
        ToolResult thursday = call("ADJUST_PLAN", Map.of("day", 1, "date", "2026-11-05"));
        assertThat(thursday.success()).isFalse();
        assertThat(thursday.message()).contains("not one of the agreed days");
        ToolResult monday = call("ADJUST_PLAN", Map.of("day", 1, "date", "2026-11-02"));
        assertThat(monday.success()).as(monday.message()).isTrue();
        assertThat(days(monday).getFirst().get("dateIso")).isEqualTo("2026-11-02");

        // "Less work per day": at most 2 Biedronki re-plans everything into more days.
        ToolResult lighter = call("ADJUST_PLAN", Map.of("biedronkaPerDay", 2));
        assertThat(lighter.success()).as(lighter.message()).isTrue();
        assertThat(days(lighter).size()).isGreaterThanOrEqualTo(4);
        assertThat(lighter.data().get("limitsPerDay")).isEqualTo(Map.of("biedronka", 2, "short", 7));

        ToolResult split = call("ADJUST_PLAN", Map.of("splitDay", 1));
        assertThat(split.success()).as(split.message()).isTrue();
        ToolResult mergedBack = call("ADJUST_PLAN", Map.of("mergeDays", List.of(1, 2)));
        assertThat(mergedBack.success()).as(mergedBack.message()).isTrue();
        assertThat(days(mergedBack)).hasSize(days(split).size() - 1);
        assertThat(days(mergedBack).stream().flatMap(d -> addresses(d).stream()).toList()).hasSize(10).doesNotHaveDuplicates();
    }

    @Test
    void acceptingSavesExactlyThePlanShownAndKeepsItForExport() {
        call("PLAN_SCHEDULE", Map.of());
        ToolResult accepted = call("ACCEPT_PLAN", Map.of());

        assertThat(accepted.success()).as(accepted.message()).isTrue();
        StoreAuditDataset dataset = service.getDataset(datasetId).orElseThrow();
        assertThat(dataset.stage()).isEqualTo(DatasetStage.SCHEDULED);
        assertThat(dataset.schedule()).hasSize(3);
        assertThat(dataset.schedule().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 4));
        assertThat(accepted.data()).containsEntry("accepted", true);
        AuditPlanner.AuditPlan kept = planner.accepted("conversation-1").orElseThrow();
        assertThat(kept.days()).hasSize(3);
        assertThat(kept.decisions()).isEmpty();
        assertThat(accepted.message()).contains("calendar__export_schedule");
    }

    @Test
    void planningNeedsLocationsFirst() {
        StoreAuditDatasetService fresh = new StoreAuditDatasetService(new QuietBus(), Clock.fixed(NOW, ZoneOffset.UTC));
        StoreDatasetTool freshTool = new StoreDatasetTool(fresh, planner);
        ToolResult created = freshTool.execute(new ToolRequest("storeDataset", "CREATE_DATASET", "c2", "r", "", "", Map.of(
                "sourceImageCount", 1, "sourceAttachmentIds", List.of("att-1"),
                "records", List.of(Map.of("network", "Żabka", "fullAddress", "Ulica 1", "sourceAttachmentId", "att-1", "sourceRow", 1)))));
        ToolResult tooEarly = freshTool.execute(new ToolRequest("storeDataset", "PLAN_SCHEDULE", "c2", "r", "", "",
                Map.of("datasetId", created.data().get("datasetId"))));
        assertThat(tooEarly.success()).isFalse();
        assertThat(tooEarly.message()).contains("cannot run while the dataset is EXTRACTED");
    }

    @Test
    void longDaysAreSplitIntoSeveralNavigationLinks() {
        List<String> stops = new ArrayList<>();
        for (int i = 1; i <= 12; i++) stops.add("Sklep " + i);
        List<String> links = GoogleMapsLinks.route("Nowa Wola", stops, "Nowa Wola");
        assertThat(links).hasSize(2);
        assertThat(links.getFirst()).contains("origin=Nowa+Wola").contains("destination=Sklep+10");
        assertThat(links.get(1)).contains("origin=Sklep+10").contains("destination=Nowa+Wola").contains("Sklep+11%7CSklep+12");
    }
}
