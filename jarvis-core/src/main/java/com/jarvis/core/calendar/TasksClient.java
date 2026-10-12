package com.jarvis.core.calendar;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.core.google.GoogleException;
import com.jarvis.core.google.GoogleOAuth;
import com.jarvis.core.google.GoogleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Google Tasks (the "Zadania" shown next to events in Google Calendar) for whichever Jarvis user asks. Tasks are a
 * separate Google API: the Calendar API never returns them. A task's due date has no time in the API.
 */
@Component
public class TasksClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{3,200}");

    /** One task list ("Moje zadania" and any the user created). */
    public record TaskList(String id, String title) {
    }

    /** One open or completed task; {@code due} is null when it has no date. */
    public record Task(String listId, String listTitle, String id, String title, String notes, LocalDate due, boolean completed) {
    }

    private final HttpClient http;
    private final GoogleOAuth oauth;
    private final URI api;

    @Autowired
    public TasksClient(GoogleOAuth oauth) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), oauth, URI.create("https://tasks.googleapis.com/tasks/v1/"));
    }

    TasksClient(HttpClient http, GoogleOAuth oauth, URI api) {
        this.http = http;
        this.oauth = oauth;
        this.api = api;
    }

    public List<TaskList> lists(String user) {
        List<TaskList> lists = new ArrayList<>();
        request(user, "GET", "users/@me/lists?maxResults=100", null).path("items")
                .forEach(item -> lists.add(new TaskList(item.path("id").asText(), item.path("title").asText(""))));
        return lists;
    }

    /** Open tasks of every list (or one named list), dated ones first by due date, then undated ones. */
    public List<Task> openTasks(String user, String listName) {
        List<Task> tasks = new ArrayList<>();
        for (TaskList list : chooseLists(user, listName)) {
            request(user, "GET", "lists/" + id(list.id()) + "/tasks?showCompleted=false&showHidden=false&maxResults=100", null)
                    .path("items").forEach(item -> {
                        if (!item.path("title").asText("").isBlank()) tasks.add(task(list, item));
                    });
        }
        tasks.sort(Comparator.comparing((Task t) -> t.due() == null).thenComparing(t -> t.due() == null ? LocalDate.MAX : t.due())
                .thenComparing(Task::title));
        return tasks;
    }

    public Task insert(String user, TaskList list, String title, String notes, LocalDate due) {
        return insert(user, list, title, notes, due, null);
    }

    /** Adds a task right after {@code previousTaskId} (null = at the top), so a day's tasks keep the route order. */
    public Task insert(String user, TaskList list, String title, String notes, LocalDate due, String previousTaskId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("title", title);
        if (notes != null && !notes.isBlank()) body.put("notes", notes);
        // The API stores only the date; Google expects it as midnight UTC.
        if (due != null) body.put("due", due + "T00:00:00.000Z");
        String path = "lists/" + id(list.id()) + "/tasks" + (previousTaskId == null ? "" : "?previous=" + id(previousTaskId));
        return task(list, request(user, "POST", path, body));
    }

    /** The task list with exactly this title, created when missing. */
    public TaskList ensureList(String user, String title) {
        for (TaskList list : lists(user)) {
            if (list.title().equalsIgnoreCase(title.strip())) return list;
        }
        JsonNode created = request(user, "POST", "users/@me/lists", Map.of("title", title.strip()));
        return new TaskList(created.path("id").asText(), created.path("title").asText(title));
    }

    /** Number of open tasks in a list (to warn before adding a plan twice). */
    public int openCount(String user, TaskList list) {
        return request(user, "GET", "lists/" + id(list.id()) + "/tasks?showCompleted=false&showHidden=false&maxResults=100", null)
                .path("items").size();
    }

    public Task complete(String user, TaskList list, String taskId) {
        return task(list, request(user, "PATCH", "lists/" + id(list.id()) + "/tasks/" + id(taskId), Map.of("status", "completed")));
    }

    /** By name when given, otherwise (reading) every list, or (writing) the first one: the default "Moje zadania". */
    public List<TaskList> chooseLists(String user, String listName) {
        List<TaskList> all = lists(user);
        if (all.isEmpty()) throw new GoogleException("The Google account has no task list.");
        if (listName == null || listName.isBlank()) return all;
        String wanted = listName.strip().toLowerCase(Locale.ROOT);
        List<TaskList> match = all.stream().filter(l -> l.title().toLowerCase(Locale.ROOT).contains(wanted)).toList();
        if (match.isEmpty()) {
            throw new GoogleException("No task list named " + listName + ". Lists: " + String.join(", ", all.stream().map(TaskList::title).toList()));
        }
        return List.of(match.getFirst());
    }

    private static Task task(TaskList list, JsonNode item) {
        String due = item.path("due").asText("");
        return new Task(list.id(), list.title(), item.path("id").asText(), item.path("title").asText(""),
                item.path("notes").asText(""), due.length() >= 10 ? LocalDate.parse(due.substring(0, 10)) : null,
                item.path("status").asText("").equals("completed"));
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
                if (status >= 200 && status < 300) {
                    return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
                }
                String reason = "";
                try { reason = JSON.readTree(response.body()).path("error").path("message").asText(""); } catch (Exception ignored) { }
                String lower = reason.toLowerCase(Locale.ROOT);
                if (status == 403 && lower.contains("insufficient")) {
                    throw new GoogleException(TASKS_NEED_RECONNECT);
                }
                if (status == 403 && (lower.contains("has not been used") || lower.contains("disabled"))) {
                    throw new GoogleException("Google Tasks API is not enabled in the Google Cloud project. The administrator enables it at "
                            + "https://console.cloud.google.com/apis/library/tasks.googleapis.com");
                }
                if (status == 401) throw new GoogleException("Google permission expired or revoked. Connect the calendar again.");
                if (status == 404) throw new GoogleException("Task or task list not found (404). Use an index from the last task list.");
                throw new GoogleException("Google Tasks rejected the request (HTTP " + status + (reason.isBlank() ? "" : ": " + reason)
                        + "). Tell the user the exact error and do not invent a cause.");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new GoogleException("Google Tasks request interrupted.");
            } catch (java.io.IOException exception) {
                throw new GoogleException("Google Tasks connection failed. Check the server's internet connection.");
            }
        }
        throw new GoogleException("Google authorization failed. Connect the calendar again.");
    }

    static final String TASKS_NEED_RECONNECT = "Tasks need one more Google permission: the user connects the calendar again "
            + "(calendar__connect) and ticks the Tasks permission. Events keep working meanwhile.";

    private static String id(String value) {
        if (value == null || !ID.matcher(value).matches()) throw new GoogleException("Use a task index from the last task list.");
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
