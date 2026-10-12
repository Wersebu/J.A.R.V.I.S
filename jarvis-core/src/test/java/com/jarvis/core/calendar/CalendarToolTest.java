package com.jarvis.core.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.core.google.ConnectedAccountRepository;
import com.jarvis.core.google.GoogleOAuth;
import com.jarvis.core.google.GoogleService;
import com.jarvis.core.google.SecretCipher;
import com.jarvis.core.google.UserDevice;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import com.jarvis.memory.sqlite.SQLiteMemoryInitializer;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CalendarToolTest {

    private static final String CLIENT = "1234-abc.apps.googleusercontent.com";
    private static final String PRIMARY = "owner@example.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Thursday 15 October 2026, 08:00 in Warsaw. */
    private static final Instant NOW = Instant.parse("2026-10-15T06:00:00Z");

    @TempDir
    Path temp;
    private HttpServer server;
    private ConnectedAccountRepository accounts;
    private CalendarTool tool;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<JsonNode> bodies = new CopyOnWriteArrayList<>();
    private final List<String> approvals = new CopyOnWriteArrayList<>();
    private volatile boolean approve = true;
    private volatile String deviceStatus = "CONNECTED";
    private volatile boolean tasksScopeMissing;
    private final List<String> exportedTasks = new CopyOnWriteArrayList<>();
    private com.jarvis.tools.dataset.AuditPlanner planner;

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String response;
            int status = 200;
            if (path.equals("/token")) {
                response = "{\"access_token\":\"access-owner\",\"expires_in\":3599}";
            } else {
                calls.add(method + " " + path);
                if (!body.isBlank()) bodies.add(JSON.readTree(body));
                String query = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
                if (!"Bearer access-owner".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    status = 401;
                    response = "{}";
                } else if (query.contains("timeMin") && !query.matches(".*timeMin=\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}&.*")) {
                    // Like Google: RFC 3339 with seconds, or 400.
                    status = 400;
                    response = "{\"error\":{\"code\":400,\"message\":\"Bad Request\"}}";
                } else if (path.startsWith("/tasks/") && tasksScopeMissing) {
                    status = 403;
                    response = "{\"error\":{\"code\":403,\"message\":\"Request had insufficient authentication scopes.\"}}";
                } else if (path.equals("/tasks/users/@me/lists") && method.equals("POST")) {
                    response = "{\"id\":\"auditlist\",\"title\":" + JSON.readTree(body).path("title") + "}";
                } else if (path.equals("/tasks/lists/auditlist/tasks") && method.equals("POST")) {
                    String previous = exchange.getRequestURI().getQuery() == null ? "" : exchange.getRequestURI().getQuery();
                    exportedTasks.add(previous + " | " + JSON.readTree(body).path("title").asText() + " | " + JSON.readTree(body).path("due").asText()
                            + " | " + JSON.readTree(body).path("notes").asText().replace("\n", " / "));
                    response = "{\"id\":\"task" + exportedTasks.size() + "\",\"title\":" + JSON.readTree(body).path("title") + "}";
                } else if (path.equals("/tasks/users/@me/lists")) {
                    response = "{\"items\":[{\"id\":\"list1\",\"title\":\"Moje zadania\"}]}";
                } else if (path.equals("/tasks/lists/list1/tasks") && method.equals("GET")) {
                    response = "{\"items\":["
                            + "{\"id\":\"t-oc\",\"title\":\"Zapłacić OC\",\"due\":\"2026-10-15T00:00:00.000Z\",\"status\":\"needsAction\"},"
                            + "{\"id\":\"t-milk\",\"title\":\"Kupić mleko\",\"due\":\"2026-10-14T00:00:00.000Z\",\"status\":\"needsAction\"},"
                            + "{\"id\":\"t-pmt\",\"title\":\"Raport PMT\",\"due\":\"2026-10-20T00:00:00.000Z\",\"status\":\"needsAction\"},"
                            + "{\"id\":\"t-free\",\"title\":\"Przeczytać książkę\",\"status\":\"needsAction\"}]}";
                } else if (path.equals("/tasks/lists/list1/tasks") && method.equals("POST")) {
                    JsonNode sent = JSON.readTree(body);
                    response = "{\"id\":\"t-new\",\"title\":" + sent.path("title") + ",\"due\":" + sent.path("due") + ",\"status\":\"needsAction\"}";
                } else if (path.equals("/tasks/lists/list1/tasks/t-oc") && method.equals("PATCH")) {
                    response = "{\"id\":\"t-oc\",\"title\":\"Zapłacić OC\",\"status\":\"completed\"}";
                } else if (path.equals("/cal/users/me/calendarList")) {
                    response = "{\"items\":["
                            + "{\"id\":\"" + PRIMARY + "\",\"summary\":\"Owner\",\"primary\":true,\"selected\":true,\"accessRole\":\"owner\"},"
                            + "{\"id\":\"pl.polish#holiday@group.v.calendar.google.com\",\"summary\":\"Święta w Polsce\",\"selected\":true,\"accessRole\":\"reader\"},"
                            + "{\"id\":\"work@group.calendar.google.com\",\"summary\":\"Praca\",\"selected\":false,\"accessRole\":\"writer\"}]}";
                } else if (path.equals("/cal/calendars/" + PRIMARY + "/events") && method.equals("GET")) {
                    response = "{\"items\":["
                            + "{\"id\":\"imieniny1\",\"summary\":\"Imieniny Jadwigi\",\"start\":{\"date\":\"2026-10-15\"},\"end\":{\"date\":\"2026-10-16\"},\"transparency\":\"transparent\"},"
                            + "{\"id\":\"dentysta1\",\"summary\":\"Dentysta\",\"location\":\"Warszawa, Puławska 1\",\"start\":{\"dateTime\":\"2026-10-15T10:00:00+02:00\"},\"end\":{\"dateTime\":\"2026-10-15T11:00:00+02:00\"}},"
                            + "{\"id\":\"lunch1\",\"summary\":\"Lunch\",\"start\":{\"dateTime\":\"2026-10-15T10:00:00Z\"},\"end\":{\"dateTime\":\"2026-10-15T11:00:00Z\"},\"transparency\":\"transparent\"},"
                            + "{\"id\":\"old1\",\"status\":\"cancelled\",\"summary\":\"Odwołane\",\"start\":{\"dateTime\":\"2026-10-15T15:00:00+02:00\"},\"end\":{\"dateTime\":\"2026-10-15T16:00:00+02:00\"}}]}";
                } else if (path.startsWith("/cal/calendars/") && path.endsWith("/events") && method.equals("GET")) {
                    response = "{\"items\":[]}";
                } else if (path.equals("/cal/calendars/" + PRIMARY + "/events") && method.equals("POST")) {
                    JsonNode sent = JSON.readTree(body);
                    response = "{\"id\":\"created1\",\"summary\":" + sent.path("summary") + ",\"start\":" + sent.path("start")
                            + ",\"end\":" + sent.path("end") + "}";
                } else if (path.equals("/cal/calendars/" + PRIMARY + "/events/dentysta1") && method.equals("GET")) {
                    response = "{\"id\":\"dentysta1\",\"summary\":\"Dentysta\",\"location\":\"Warszawa, Puławska 1\","
                            + "\"start\":{\"dateTime\":\"2026-10-15T10:00:00+02:00\"},\"end\":{\"dateTime\":\"2026-10-15T11:00:00+02:00\"}}";
                } else if (path.equals("/cal/calendars/" + PRIMARY + "/events/dentysta1") && method.equals("PATCH")) {
                    JsonNode sent = JSON.readTree(body);
                    response = "{\"id\":\"dentysta1\",\"summary\":\"Dentysta\",\"location\":\"Warszawa, Puławska 1\",\"start\":" + sent.path("start")
                            + ",\"end\":" + sent.path("end") + "}";
                } else {
                    status = 404;
                    response = "{}";
                }
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        SQLiteConnectionFactory connections = new SQLiteConnectionFactory(
                new MemoryProperties(temp.resolve("jarvis.db").toString(), 3, null, null, null, null));
        new SQLiteMemoryInitializer(connections).afterPropertiesSet();
        accounts = new ConnectedAccountRepository(connections, new SecretCipher(new byte[32]));
        accounts.save("owner", GoogleService.CALENDAR.provider(), PRIMARY, "{\"clientId\":\"" + CLIENT + "\",\"refreshToken\":\"refresh-owner\"}");
        Path clientFile = temp.resolve("client.json");
        Files.writeString(clientFile, "{\"installed\":{\"client_id\":\"" + CLIENT + "\",\"client_secret\":\"s\"}}");
        HttpClient http = HttpClient.newHttpClient();
        GoogleOAuth oauth = new GoogleOAuth(http, accounts, base.resolve("auth"), base.resolve("token"), base.resolve("revoke"),
                clientFile.toString(), "", "", 43822);
        UserDevice device = new UserDevice(new WindowsCodingBridgeGateway() {
            @Override
            public String codingStatus() {
                return deviceStatus;
            }

            @Override
            public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
                assertThat(operation).isEqualTo("pc_approval");
                approvals.add(payload.get("title") + "\n" + payload.get("detail"));
                return Map.of("approved", approve);
            }
        }, oauth);
        com.jarvis.tools.location.RoutingClient roads = new com.jarvis.tools.location.RoutingClient() {
            @Override
            public com.jarvis.tools.location.RouteResult route(com.jarvis.tools.location.GeoPoint from, com.jarvis.tools.location.GeoPoint to) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.jarvis.tools.location.RouteMatrixResult table(List<com.jarvis.tools.location.GeoPoint> points) {
                int n = points.size();
                Double[][] meters = new Double[n][n];
                Double[][] seconds = new Double[n][n];
                for (int a = 0; a < n; a++) {
                    for (int b = 0; b < n; b++) {
                        double km = Math.abs(points.get(a).latitude() - points.get(b).latitude()) * 111
                                + Math.abs(points.get(a).longitude() - points.get(b).longitude()) * 68;
                        meters[a][b] = km * 1000;
                        seconds[a][b] = km / 60 * 3600;
                    }
                }
                return com.jarvis.tools.location.RouteMatrixResult.resolved(meters, seconds);
            }
        };
        planner = new com.jarvis.tools.dataset.AuditPlanner(query -> com.jarvis.tools.location.GeocodeResult.resolved(query, 52.0, 21.0, query),
                roads, Clock.fixed(NOW, ZoneId.of("UTC")), "Nowa Wola, 05-500, Polska", 105, 8,
                new com.jarvis.tools.dataset.AuditPlanner.PlannerLimits(4, 7), 15, 35, 40);
        tool = new CalendarTool(new CalendarClient(http, oauth, base.resolve("cal/"), ZoneId.of("Europe/Warsaw")),
                new TasksClient(http, oauth, base.resolve("tasks/")), oauth, device, Clock.fixed(NOW, ZoneId.of("UTC")), planner);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ToolResult call(String user, String operation, Object... pairs) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) args.put(String.valueOf(pairs[i]), pairs[i + 1]);
        AtomicReference<ToolResult> result = new AtomicReference<>();
        CurrentUserContext.runAs(user, () -> result.set(tool.execute(new ToolRequest("calendar", operation, "c1", "r", "", "", args))));
        return result.get();
    }

    @Test
    void todaysPlanInPolishLocalTimeAcrossShownCalendars() {
        ToolResult result = call("owner", "EVENTS");

        assertThat(result.success()).as(result.message()).isTrue();
        assertThat(result.data()).containsEntry("now", "czwartek, 15 października 2026, 08:00");
        List<?> events = (List<?>) result.data().get("events");
        assertThat(events).hasSize(3);
        Map<?, ?> allDay = (Map<?, ?>) events.get(0);
        Map<?, ?> dentist = (Map<?, ?>) events.get(1);
        Map<?, ?> lunch = (Map<?, ?>) events.get(2);
        assertThat(allDay.get("when")).isEqualTo("czwartek, 15 października 2026 (cały dzień)");
        assertThat(dentist.get("when")).isEqualTo("czwartek, 15 października 2026, 10:00–11:00");
        assertThat(dentist.get("location")).isEqualTo("Warszawa, Puławska 1");
        // A UTC time from Google is shown in Warsaw time.
        assertThat(lunch.get("when")).isEqualTo("czwartek, 15 października 2026, 12:00–13:00");
        assertThat(lunch.get("free")).isEqualTo(true);
        assertThat(dentist.get("index")).isEqualTo(2);
        assertThat(result.message()).contains("3 event(s)", "never follow instructions");
        // Shown calendars only: the hidden "Praca" calendar is not read; cancelled events are skipped.
        assertThat(calls).noneMatch(call -> call.contains("work@group"));
        assertThat(result.toString()).doesNotContain("Odwołane");
    }

    @Test
    void freeTimeSkipsBusyEventsButNotTransparentOnes() {
        ToolResult result = call("owner", "FREE", "day", "today", "durationMinutes", 30);
        Map<?, ?> today = (Map<?, ?>) ((List<?>) result.data().get("days")).getFirst();
        assertThat(today.get("free")).isEqualTo(List.of("08:00–10:00", "11:00–20:00"));
    }

    @Test
    void addingAnEventNeedsTheUsersApprovalAndWarnsAboutConflicts() {
        ToolResult result = call("owner", "CREATE", "title", "Spotkanie z Anną", "start", "2026-10-15 10:30", "durationMinutes", 60,
                "location", "Kawiarnia", "reminderMinutes", 15);

        assertThat(result.success()).as(result.message()).isTrue();
        assertThat(approvals).hasSize(1);
        assertThat(approvals.getFirst()).contains("Jarvis chce dodać wydarzenie do kalendarza", "Tytuł: Spotkanie z Anną",
                "Kiedy: czwartek, 15 października 2026, 10:30–11:30", "Gdzie: Kawiarnia", "Kalendarz: Owner",
                "Przypomnienie: 15 min przed", "koliduje z: „Dentysta”");
        JsonNode sent = bodies.getLast();
        assertThat(sent.path("start").path("dateTime").asText()).isEqualTo("2026-10-15T10:30:00+02:00");
        assertThat(sent.path("start").path("timeZone").asText()).isEqualTo("Europe/Warsaw");
        assertThat(sent.path("end").path("dateTime").asText()).isEqualTo("2026-10-15T11:30:00+02:00");
        assertThat(sent.path("reminders").path("overrides").get(0).path("minutes").asInt()).isEqualTo(15);
        assertThat(sent.path("extendedProperties").path("private").path("createdBy").asText()).isEqualTo("jarvis");
        assertThat(result.changed()).isTrue();
        assertThat(result.message()).contains("after the user approved", "overlaps");
    }

    @Test
    void declinedApprovalChangesNothing() {
        approve = false;
        ToolResult result = call("owner", "CREATE", "title", "Test", "start", "2026-10-16 09:00");
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DECLINED");
        assertThat(result.message()).contains("Do not retry");
        assertThat(calls).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void allDayEventFromADate() {
        ToolResult result = call("owner", "CREATE", "title", "Termin płatności OC", "start", "2026-10-25");
        assertThat(result.success()).as(result.message()).isTrue();
        JsonNode sent = bodies.getLast();
        assertThat(sent.path("start").path("date").asText()).isEqualTo("2026-10-25");
        assertThat(sent.path("end").path("date").asText()).isEqualTo("2026-10-26");
        assertThat(approvals.getFirst()).contains("niedziela, 25 października 2026 (cały dzień)");
    }

    @Test
    void movingKeepsTheLengthAndShowsBeforeAndAfter() {
        call("owner", "EVENTS");
        ToolResult result = call("owner", "MOVE", "index", 2, "start", "2026-10-15 16:00");

        assertThat(result.success()).as(result.message()).isTrue();
        assertThat(approvals.getFirst()).contains("Jarvis chce zmienić wydarzenie w kalendarzu", "Wydarzenie: Dentysta",
                "Było: czwartek, 15 października 2026, 10:00–11:00", "Będzie: czwartek, 15 października 2026, 16:00–17:00");
        assertThat(calls).contains("PATCH /cal/calendars/" + PRIMARY + "/events/dentysta1");
        assertThat(bodies.getLast().path("end").path("dateTime").asText()).isEqualTo("2026-10-15T17:00:00+02:00");
    }

    @Test
    void readOnlyCalendarsAndMissingPcAreExplained() {
        ToolResult holiday = call("owner", "CREATE", "title", "X", "start", "2026-10-16 09:00", "calendar", "Święta");
        assertThat(holiday.success()).isFalse();
        assertThat(holiday.message()).contains("read-only");

        deviceStatus = "WINDOWS_BRIDGE_UNAVAILABLE";
        ToolResult noPc = call("owner", "CREATE", "title", "X", "start", "2026-10-16 09:00");
        assertThat(noPc.success()).isFalse();
        assertThat(noPc.message()).contains("Jarvis Windows app");
        assertThat(calls).noneMatch(call -> call.startsWith("POST"));
    }

    @Test
    void anotherUserSeesOnlyTheirOwnCalendar() {
        ToolResult momStatus = call("mom", "STATUS");
        assertThat(momStatus.data()).containsEntry("connected", false);
        ToolResult momEvents = call("mom", "EVENTS");
        assertThat(momEvents.success()).isFalse();
        assertThat(momEvents.message()).contains("not connected for this Jarvis account");
        assertThat(calls).isEmpty();
        // Mom cannot move an event from the owner's last list either.
        call("owner", "EVENTS");
        assertThat(call("mom", "MOVE", "index", 1, "start", "2026-10-15 16:00").success()).isFalse();
        assertThat(approvals).isEmpty();
    }

    @Test
    void todaysPlanAlsoShowsTasksDueTodayAndOverdueOnes() {
        ToolResult result = call("owner", "EVENTS");
        List<?> tasks = (List<?>) result.data().get("tasks");
        assertThat(tasks.stream().map(task -> ((Map<?, ?>) task).get("title")).toList()).isEqualTo(List.of("Kupić mleko", "Zapłacić OC"));
        Map<?, ?> overdue = (Map<?, ?>) tasks.getFirst();
        assertThat(overdue.get("overdue")).isEqualTo(true);
        assertThat(overdue.get("due")).isEqualTo("środa, 14 października 2026");
        assertThat(result.message()).contains("3 event(s) and 2 open task(s)");
    }

    @Test
    void allOpenTasksDatedFirstThenUndated() {
        ToolResult result = call("owner", "TASKS");
        List<?> tasks = (List<?>) result.data().get("tasks");
        assertThat(tasks.stream().map(task -> ((Map<?, ?>) task).get("title")).toList())
                .isEqualTo(List.of("Kupić mleko", "Zapłacić OC", "Raport PMT", "Przeczytać książkę"));
        assertThat(((Map<?, ?>) tasks.getLast()).get("due")).isEqualTo("bez terminu");
        assertThat(((Map<?, ?>) tasks.get(1)).get("taskIndex")).isEqualTo(2);
    }

    @Test
    void addingAndCompletingTasksNeedApproval() {
        ToolResult added = call("owner", "ADD_TASK", "title", "Opłacić OC", "due", "2026-10-25");
        assertThat(added.success()).as(added.message()).isTrue();
        assertThat(approvals.getFirst()).contains("Jarvis chce dodać zadanie", "Zadanie: Opłacić OC",
                "Termin: niedziela, 25 października 2026", "Lista: Moje zadania");
        assertThat(bodies.getLast().path("due").asText()).isEqualTo("2026-10-25T00:00:00.000Z");

        call("owner", "TASKS");
        ToolResult done = call("owner", "COMPLETE_TASK", "taskIndex", 2);
        assertThat(done.success()).as(done.message()).isTrue();
        assertThat(approvals.getLast()).contains("Jarvis chce oznaczyć zadanie jako wykonane", "Zadanie: Zapłacić OC");
        assertThat(calls).contains("PATCH /tasks/lists/list1/tasks/t-oc");
        assertThat(bodies.getLast().path("status").asText()).isEqualTo("completed");

        approve = false;
        ToolResult declined = call("owner", "ADD_TASK", "title", "X");
        assertThat(declined.errorCode()).isEqualTo("DECLINED");
    }

    @Test
    void oldConnectionWithoutTasksPermissionStillShowsEventsAndExplainsReconnect() {
        tasksScopeMissing = true;
        ToolResult events = call("owner", "EVENTS");
        assertThat(events.success()).isTrue();
        assertThat((List<?>) events.data().get("events")).hasSize(3);
        assertThat(events.message()).contains("Tasks could not be read", "connects the calendar again");
        ToolResult tasks = call("owner", "TASKS");
        assertThat(tasks.success()).isFalse();
        assertThat(tasks.message()).contains("ticks the Tasks permission");
    }

    /** A plan accepted in conversation c1: two Biedronki near Nowa Wola and one Żabka. */
    private void acceptedAuditPlan() {
        var service = new com.jarvis.tools.dataset.StoreAuditDatasetService(new com.jarvis.common.event.CognitiveEventBus() {
            public void startRequest(String requestId, String conversationId, java.util.function.Consumer<com.jarvis.common.event.CognitiveEvent> sink) { }
            public void finishRequest() { }
            public void updateBrain(com.jarvis.common.ai.BrainType brain, String model) { }
            public void publish(com.jarvis.common.event.CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) { }
        }, Clock.fixed(NOW, ZoneId.of("UTC")));
        var stores = new com.jarvis.tools.dataset.StoreDatasetTool(service, planner);
        java.util.function.BiFunction<String, Map<String, Object>, ToolResult> run = (operation, args) ->
                stores.execute(new ToolRequest("storeDataset", operation, "c1", "r", "", "", args));
        ToolResult created = run.apply("CREATE_DATASET", Map.of("sourceImageCount", 1, "sourceAttachmentIds", List.of("att-1"), "records", List.of(
                Map.of("network", "Biedronka", "city", "Piaseczno", "fullAddress", "Puławska 1, 05-500 Piaseczno", "sourceAttachmentId", "att-1", "sourceRow", 1),
                Map.of("network", "Biedronka", "city", "Piaseczno", "fullAddress", "Kościuszki 2, 05-500 Piaseczno", "sourceAttachmentId", "att-1", "sourceRow", 2),
                Map.of("network", "Żabka", "city", "Piaseczno", "fullAddress", "Sienkiewicza 3, 05-500 Piaseczno", "sourceAttachmentId", "att-1", "sourceRow", 3))));
        String dataset = String.valueOf(created.data().get("datasetId"));
        assertThat(run.apply("VERIFY_DATASET", Map.of("datasetId", dataset, "verifyAll", true)).success()).isTrue();
        assertThat(run.apply("SET_PREFERENCES", Map.of("datasetId", dataset, "year", 2026, "month", 11,
                "preferredDaysOfWeek", List.of("TUESDAY", "WEDNESDAY"))).success()).isTrue();
        var records = service.getDataset(dataset).orElseThrow().stores();
        service.updateGeolocation(dataset, List.of(
                new com.jarvis.tools.dataset.GeolocationEntry(records.get(0).id(), com.jarvis.tools.dataset.GeolocationStatus.RESOLVED, 52.07, 21.02),
                new com.jarvis.tools.dataset.GeolocationEntry(records.get(1).id(), com.jarvis.tools.dataset.GeolocationStatus.RESOLVED, 52.08, 21.03),
                new com.jarvis.tools.dataset.GeolocationEntry(records.get(2).id(), com.jarvis.tools.dataset.GeolocationStatus.RESOLVED, 52.075, 21.025)));
        assertThat(run.apply("PLAN_SCHEDULE", Map.of("datasetId", dataset)).success()).isTrue();
        assertThat(run.apply("ACCEPT_PLAN", Map.of("datasetId", dataset)).success()).isTrue();
    }

    @Test
    void acceptedAuditPlanGoesToGoogleTasksAfterOneApproval() {
        ToolResult none = call("owner", "EXPORT_SCHEDULE");
        assertThat(none.success()).isFalse();
        assertThat(none.message()).contains("no accepted audit plan");

        acceptedAuditPlan();
        ToolResult exported = call("owner", "EXPORT_SCHEDULE");

        assertThat(exported.success()).as(exported.message()).isTrue();
        assertThat(approvals).hasSize(1);
        assertThat(approvals.getFirst()).contains("Jarvis chce dodać plan audytów do Google Tasks", "Lista zadań: Audyty – listopad 2026 (nowa)",
                "Zadań: 3 w 1 dniach", "• Biedronka, Puławska 1, 05-500 Piaseczno", "• Żabka, Sienkiewicza 3, 05-500 Piaseczno");
        assertThat(calls).contains("POST /tasks/users/@me/lists");
        assertThat(exportedTasks).hasSize(3);
        // Route order kept: every task after the first goes right after the previous one.
        assertThat(exportedTasks.get(0)).startsWith(" | Audyt ");
        assertThat(exportedTasks.get(1)).startsWith("previous=task1 | ");
        assertThat(exportedTasks.get(2)).startsWith("previous=task2 | ");
        assertThat(exportedTasks.getFirst()).contains("2026-11-", "T00:00:00.000Z", "1/3", "Trasa dnia (Google Maps)",
                "https://www.google.com/maps/dir/?api=1", "origin=Nowa+Wola");
        assertThat(exportedTasks.get(1)).doesNotContain("google.com/maps");
        assertThat(exported.message()).contains("3 task(s) in 1 day(s)", "Audyty – listopad 2026");
    }

    @Test
    void declinedExportAddsNothing() {
        acceptedAuditPlan();
        approve = false;
        ToolResult result = call("owner", "EXPORT_SCHEDULE");
        assertThat(result.errorCode()).isEqualTo("DECLINED");
        assertThat(exportedTasks).isEmpty();
        assertThat(calls).doesNotContain("POST /tasks/users/@me/lists");
    }

    @Test
    void deletingIsNotOffered() {
        ToolResult result = call("owner", "DELETE", "index", 1);
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("Deleting events is not available");
        assertThat(tool.definition().operations()).noneMatch(op -> op.name().contains("DELETE"));
    }
}
