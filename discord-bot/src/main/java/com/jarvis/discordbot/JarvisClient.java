package com.jarvis.discordbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the small slice of the Jarvis Core API this bot needs:
 * authenticate as a dedicated user, publish that user's global prompt (the persona),
 * and send chat turns. The session token is cached and transparently refreshed on a 401.
 */
public final class JarvisClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(JarvisClient.class);

    private final String baseUrl;
    private final String email;
    private final String password;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    private volatile String token;

    public JarvisClient(BotConfig config) {
        this.baseUrl = config.jarvisBaseUrl;
        this.email = config.jarvisEmail;
        this.password = config.jarvisPassword;
        this.requestTimeout = Duration.ofSeconds(config.requestTimeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    /** Logs in and caches the session token. Throws if Core rejects the credentials. */
    public synchronized void login() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("password", password);

        HttpResponse<String> response = send(
                HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(bodyOf(body)));

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Jarvis login failed (HTTP " + response.statusCode()
                    + "): " + response.body());
        }
        this.token = readField(response.body(), "accessToken");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("Jarvis login returned no accessToken: " + response.body());
        }
        LOGGER.info("Logged in to Jarvis Core as {}", email);
    }

    /** Publishes the persona as this user's global prompt, so Core applies it to every chat turn. */
    public void publishPersona(String persona) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("globalPrompt", persona);

        HttpResponse<String> response = withAuthRetry(() ->
                HttpRequest.newBuilder(uri("/api/v1/users/me/settings"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .PUT(bodyOf(body)));

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Publishing persona failed (HTTP " + response.statusCode()
                    + "): " + response.body());
        }
        LOGGER.info("Persona published to Jarvis Core as the user's global prompt ({} chars).", persona.length());
    }

    /**
     * Sends one chat turn and returns Jarvis's reply text.
     *
     * @param conversationId stable id (one per Discord channel) so Core keeps per-channel history
     * @param message        the user message to answer
     * @return the plain-text reply, or empty string if Core returned nothing usable
     */
    public String chat(String conversationId, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversationId", conversationId);
        body.put("message", message);
        body.put("knowledgeMode", "AUTO");
        body.put("attachments", List.of());

        HttpResponse<String> response = withAuthRetry(() ->
                HttpRequest.newBuilder(uri("/api/v1/chat"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .timeout(requestTimeout)
                        .POST(bodyOf(body)));

        if (response.statusCode() != 200) {
            throw new IllegalStateException("Jarvis chat failed (HTTP " + response.statusCode()
                    + "): " + response.body());
        }
        String reply = readField(response.body(), "response");
        return reply == null ? "" : reply.strip();
    }

    /**
     * Runs a request, and if Core answers 401 (token expired or missing) logs in once and retries.
     * The request is built lazily by the supplier so the retry picks up the fresh token.
     */
    private HttpResponse<String> withAuthRetry(java.util.function.Supplier<HttpRequest.Builder> builder) {
        if (token == null) {
            login();
        }
        HttpResponse<String> response = send(builder.get());
        if (response.statusCode() == 401) {
            LOGGER.info("Jarvis token rejected (401); re-authenticating.");
            login();
            response = send(builder.get());
        }
        return response;
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("HTTP call to Jarvis Core failed: " + exception, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling Jarvis Core", exception);
        }
    }

    private String readField(String bodyText, String field) {
        try {
            JsonNode node = json.readTree(bodyText);
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : value.asText();
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse Jarvis response: " + bodyText, exception);
        }
    }

    private HttpRequest.BodyPublisher bodyOf(Map<String, Object> body) {
        try {
            return HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize request body", exception);
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }
}
