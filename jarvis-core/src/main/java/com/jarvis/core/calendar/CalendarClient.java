package com.jarvis.core.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.core.google.GoogleException;
import com.jarvis.core.google.GoogleOAuth;
import com.jarvis.core.google.GoogleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google Calendar API for whichever Jarvis user asks: calendars, events in a time range, adding and changing events.
 * Times are converted to the user's zone (Europe/Warsaw by default, also when the server runs in UTC).
 */
@Component
public class CalendarClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern EVENT_ID = Pattern.compile("[A-Za-z0-9_-]{3,1024}");
    /**
     * Google requires RFC 3339 with seconds; OffsetDateTime.toString() drops ":00" seconds ("2026-10-12T00:00+02:00")
     * and Google answers 400 to that.
     */
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    /** One calendar of the user. */
    public record CalendarInfo(String id, String name, boolean primary, boolean selected, String accessRole) {
        public boolean writable() {
            return accessRole.equals("owner") || accessRole.equals("writer");
        }
    }

    /** One event; {@code end} is exclusive (an all-day event on the 15th ends on the 16th at 00:00). */
    public record CalendarEvent(String calendarId, String calendarName, String id, String title, ZonedDateTime start,
                                ZonedDateTime end, boolean allDay, String location, String description, boolean busy) {
    }

    private final HttpClient http;
    private final GoogleOAuth oauth;
    private final URI api;
    private final ZoneId zone;

    @Autowired
    public CalendarClient(GoogleOAuth oauth, @Value("${jarvis.calendar.time-zone:${jarvis.gmail.time-zone:Europe/Warsaw}}") String zone) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), oauth,
                URI.create("https://www.googleapis.com/calendar/v3/"), ZoneId.of(zone));
    }

    CalendarClient(HttpClient http, GoogleOAuth oauth, URI api, ZoneId zone) {
        this.http = http;
        this.oauth = oauth;
        this.api = api;
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    public List<CalendarInfo> calendars(String user) {
        List<CalendarInfo> calendars = new ArrayList<>();
        request(user, "GET", "users/me/calendarList?minAccessRole=reader&maxResults=250", null).path("items").forEach(item -> calendars.add(
                new CalendarInfo(item.path("id").asText(), item.path("summaryOverride").asText(item.path("summary").asText("")),
                        item.path("primary").asBoolean(false), item.path("selected").asBoolean(false), item.path("accessRole").asText(""))));
        calendars.sort(Comparator.comparing((CalendarInfo c) -> !c.primary()).thenComparing(CalendarInfo::name));
        return calendars;
    }

    /** The account address: the id of the primary calendar. */
    public String primaryEmail(String user) {
        return calendars(user).stream().filter(CalendarInfo::primary).map(CalendarInfo::id).findFirst().orElse("");
    }

    /**
     * Events overlapping [from, to) in the calendars the user shows in Google Calendar (or one named calendar).
     */
    public List<CalendarEvent> events(String user, ZonedDateTime from, ZonedDateTime to, String query, String calendar) {
        List<CalendarInfo> chosen = chooseCalendars(user, calendar, false);
        List<CalendarEvent> events = new ArrayList<>();
        for (CalendarInfo info : chosen) {
            StringBuilder path = new StringBuilder("calendars/").append(encode(info.id())).append("/events?singleEvents=true&orderBy=startTime")
                    .append("&maxResults=250&timeMin=").append(encode(RFC3339.format(from)))
                    .append("&timeMax=").append(encode(RFC3339.format(to)));
            if (query != null && !query.isBlank()) path.append("&q=").append(encode(query.strip()));
            request(user, "GET", path.toString(), null).path("items").forEach(item -> {
                if (!item.path("status").asText("").equals("cancelled")) events.add(event(info, item));
            });
        }
        events.sort(Comparator.comparing(CalendarEvent::start).thenComparing(CalendarEvent::title));
        return events;
    }

    public CalendarEvent get(String user, CalendarInfo calendar, String eventId) {
        return event(calendar, request(user, "GET", "calendars/" + encode(calendar.id()) + "/events/" + eventId(eventId), null));
    }

    public CalendarEvent insert(String user, CalendarInfo calendar, Map<String, Object> body) {
        return event(calendar, request(user, "POST", "calendars/" + encode(calendar.id()) + "/events", body));
    }

    public CalendarEvent patch(String user, CalendarInfo calendar, String eventId, Map<String, Object> body) {
        return event(calendar, request(user, "PATCH", "calendars/" + encode(calendar.id()) + "/events/" + eventId(eventId), body));
    }

    /**
     * Calendars to read or write: by name or id when given, otherwise (reading) every calendar the user shows in
     * Google Calendar, or (writing) the primary one.
     */
    public List<CalendarInfo> chooseCalendars(String user, String calendar, boolean forWriting) {
        List<CalendarInfo> all = calendars(user);
        if (calendar != null && !calendar.isBlank()) {
            String wanted = calendar.strip().toLowerCase(Locale.ROOT);
            List<CalendarInfo> match = all.stream().filter(c -> c.id().equalsIgnoreCase(wanted) || c.name().toLowerCase(Locale.ROOT).equals(wanted)).toList();
            if (match.isEmpty()) {
                match = all.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).contains(wanted)).toList();
            }
            if (match.isEmpty()) {
                throw new GoogleException("No calendar named " + calendar + ". Calendars: "
                        + String.join(", ", all.stream().map(CalendarInfo::name).toList()));
            }
            return List.of(match.getFirst());
        }
        if (forWriting) {
            return all.stream().filter(CalendarInfo::primary).findFirst().map(List::of)
                    .orElseThrow(() -> new GoogleException("The Google account has no primary calendar."));
        }
        List<CalendarInfo> shown = all.stream().filter(c -> c.primary() || c.selected()).toList();
        return shown.isEmpty() ? all : shown;
    }

    private CalendarEvent event(CalendarInfo calendar, JsonNode item) {
        boolean allDay = item.path("start").has("date");
        ZonedDateTime start = time(item.path("start"));
        ZonedDateTime end = item.path("end").isMissingNode() ? start.plusHours(1) : time(item.path("end"));
        boolean declined = false;
        for (JsonNode attendee : item.path("attendees")) {
            if (attendee.path("self").asBoolean(false) && attendee.path("responseStatus").asText("").equals("declined")) declined = true;
        }
        boolean busy = !item.path("transparency").asText("opaque").equals("transparent") && !declined;
        String description = item.path("description").asText("");
        if (description.length() > 300) description = description.substring(0, 300) + "…";
        return new CalendarEvent(calendar.id(), calendar.name(), item.path("id").asText(),
                item.path("summary").asText("").isBlank() ? "(bez tytułu)" : item.path("summary").asText(),
                start, end, allDay, item.path("location").asText(""), description, busy);
    }

    private ZonedDateTime time(JsonNode node) {
        if (node.has("dateTime")) {
            return OffsetDateTime.parse(node.path("dateTime").asText()).atZoneSameInstant(zone);
        }
        return LocalDate.parse(node.path("date").asText()).atStartOfDay(zone);
    }

    private JsonNode request(String user, String method, String path, Object body) {
        String token = oauth.accessToken(user, GoogleService.CALENDAR, false);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(api.resolve(path)).timeout(Duration.ofSeconds(20))
                        .header("Authorization", "Bearer " + token);
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
                            .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
                }
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 401 && attempt == 0) {
                    token = oauth.accessToken(user, GoogleService.CALENDAR, true);
                    continue;
                }
                if (status == 401) throw new GoogleException("Google Calendar permission expired or revoked. Connect the calendar again.");
                if (status == 403) throw new GoogleException("Google Calendar denied this request (403): the Google Calendar API must be "
                        + "enabled in the Google Cloud project, or this calendar is read-only for the user.");
                if (status == 404) throw new GoogleException("Calendar or event not found (404). Use an index from the last list.");
                if (status == 429) throw new GoogleException("Google Calendar rate limit. Try again in a minute; do not loop.");
                if (status < 200 || status >= 300) {
                    // Google's own reason ("Bad Request", "Invalid time range"...) keeps the model from guessing a cause.
                    String reason = "";
                    try { reason = JSON.readTree(response.body()).path("error").path("message").asText(""); } catch (Exception ignored) { }
                    throw new GoogleException("Google Calendar rejected the request (HTTP " + status + (reason.isBlank() ? "" : ": " + reason)
                            + "). This is not a login problem; tell the user the exact error and do not invent a cause.");
                }
                return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GoogleException("Google Calendar request interrupted.");
            } catch (java.io.IOException exception) {
                throw new GoogleException("Google Calendar connection failed. Check the server's internet connection.");
            }
        }
        throw new GoogleException("Google Calendar authorization failed. Connect the calendar again.");
    }

    /** Google time for a body: {"dateTime": "...+02:00", "timeZone": "Europe/Warsaw"} or {"date": "2026-10-15"}. */
    Map<String, Object> googleTime(ZonedDateTime time, boolean allDay) {
        return allDay ? Map.of("date", time.toLocalDate().toString())
                : Map.of("dateTime", RFC3339.format(time), "timeZone", zone.getId());
    }

    private static String eventId(String id) {
        if (id == null || !EVENT_ID.matcher(id).matches()) throw new GoogleException("Use an event index from the last calendar list.");
        return id;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
