package com.jarvis.core.google;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Google OAuth for every Jarvis user and every {@link GoogleService}, done by Core: PKCE, code exchange, refresh and
 * revoke. One Google Cloud OAuth client (the administrator's) serves all users; each user consents with their own
 * Google account and gets their own encrypted refresh token per service in {@link ConnectedAccountRepository}. The
 * browser part runs on a device of that user; the PKCE verifier never leaves Core, so the one-time code passing
 * through the device is useless alone.
 */
@Component
public class GoogleOAuth {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** A started consent: the URL the user opens and what Core needs to finish it. */
    public record Authorization(GoogleService service, String url, String state, String redirectUri, String verifier, Instant deadline) {
    }

    private record Access(String token, Instant expires) {
    }

    private final HttpClient http;
    private final ConnectedAccountRepository accounts;
    private final URI authorize;
    private final URI tokenUri;
    private final URI revoke;
    private final String clientFile;
    private final String clientIdSetting;
    private final String clientSecretSetting;
    private final int callbackPort;
    private final Map<String, Access> access = new ConcurrentHashMap<>();

    @Autowired
    public GoogleOAuth(ConnectedAccountRepository accounts,
                       @Value("${jarvis.google.client-file:${jarvis.gmail.client-file:${JARVIS_GMAIL_CLIENT_FILE:}}}") String clientFile,
                       @Value("${jarvis.google.client-id:${jarvis.gmail.client-id:${JARVIS_GMAIL_CLIENT_ID:}}}") String clientId,
                       @Value("${jarvis.google.client-secret:${jarvis.gmail.client-secret:${JARVIS_GMAIL_CLIENT_SECRET:}}}") String clientSecret,
                       @Value("${jarvis.google.callback-port:${jarvis.gmail.callback-port:43822}}") int callbackPort) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(), accounts,
                URI.create("https://accounts.google.com/o/oauth2/v2/auth"), URI.create("https://oauth2.googleapis.com/token"),
                URI.create("https://oauth2.googleapis.com/revoke"), clientFile, clientId, clientSecret, callbackPort);
    }

    public GoogleOAuth(HttpClient http, ConnectedAccountRepository accounts, URI authorize, URI tokenUri, URI revoke,
                       String clientFile, String clientId, String clientSecret, int callbackPort) {
        this.http = http;
        this.accounts = accounts;
        this.authorize = authorize;
        this.tokenUri = tokenUri;
        this.revoke = revoke;
        this.clientFile = clientFile == null ? "" : clientFile.strip();
        this.clientIdSetting = clientId == null ? "" : clientId.strip();
        this.clientSecretSetting = clientSecret == null ? "" : clientSecret.strip();
        this.callbackPort = callbackPort;
    }

    /** The OAuth client from the downloaded Google JSON ({"installed": {...}}) or from explicit settings. */
    private String[] client() {
        String id = clientIdSetting;
        String secret = clientSecretSetting;
        if (id.isBlank() && !clientFile.isBlank()) {
            try {
                JsonNode root = JSON.readTree(Files.readString(Path.of(clientFile)));
                JsonNode node = root.has("installed") ? root.path("installed") : root.has("web") ? root.path("web") : root;
                id = node.path("client_id").asText("");
                secret = node.path("client_secret").asText("");
            } catch (Exception exception) {
                throw new GoogleException("Cannot read the Google OAuth client file " + clientFile + " on the Jarvis server.");
            }
        }
        return new String[] {id.strip(), secret.strip()};
    }

    public boolean configured() {
        return client()[0].matches("[A-Za-z0-9._-]{8,200}\\.apps\\.googleusercontent\\.com");
    }

    public int callbackPort() {
        return callbackPort;
    }

    public boolean connected(String userId, GoogleService service) {
        return accounts.find(userId, service.provider()).isPresent();
    }

    public String email(String userId, GoogleService service) {
        return accounts.find(userId, service.provider()).map(ConnectedAccountRepository.ConnectedAccount::accountEmail).orElse("");
    }

    public Map<String, Object> status(String userId, GoogleService service) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured());
        var account = accounts.find(userId, service.provider());
        result.put("connected", account.isPresent());
        result.put("email", account.map(ConnectedAccountRepository.ConnectedAccount::accountEmail).orElse(""));
        return result;
    }

    /** Starts consent for one service with a loopback redirect on the user's device. */
    public Authorization begin(GoogleService service) {
        if (!configured()) {
            throw new GoogleException(service.label() + " is not set up on the Jarvis server: the administrator must put the Google "
                    + "OAuth client (Desktop app JSON) on the server and set JARVIS_GMAIL_CLIENT_FILE.");
        }
        String verifier = random();
        String state = random();
        String redirect = "http://127.0.0.1:" + callbackPort + "/callback";
        Map<String, String> query = new LinkedHashMap<>();
        query.put("client_id", client()[0]);
        query.put("response_type", "code");
        query.put("redirect_uri", redirect);
        query.put("scope", String.join(" ", service.scopes()));
        query.put("state", state);
        query.put("code_challenge_method", "S256");
        query.put("code_challenge", challenge(verifier));
        // offline + consent: Google returns a refresh token on every connect, also after a previous one.
        query.put("access_type", "offline");
        query.put("prompt", "consent");
        return new Authorization(service, authorize + "?" + form(query), state, redirect, verifier, Instant.now().plusSeconds(300));
    }

    /** Exchanges the code from the consent page and stores the user's encrypted refresh token for that service. */
    public void complete(String userId, Authorization authorization, String code) {
        GoogleService service = authorization.service();
        if (Instant.now().isAfter(authorization.deadline())) {
            throw new GoogleException("The Google login took too long. Ask to connect " + service.label() + " again.");
        }
        String[] client = client();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("grant_type", "authorization_code");
        values.put("code", code);
        values.put("redirect_uri", authorization.redirectUri());
        values.put("client_id", client[0]);
        values.put("code_verifier", authorization.verifier());
        if (!client[1].isBlank()) values.put("client_secret", client[1]);
        JsonNode tokens = tokenRequest(values, service);
        // Google's consent screen lets the user untick permissions; without them every call would fail with 403.
        if (tokens.has("scope")) {
            List<String> granted = Arrays.asList(tokens.path("scope").asText("").split(" "));
            if (!granted.containsAll(service.scopes())) {
                throw new GoogleException(service.label() + " permissions were not all granted. Connect again and tick every "
                        + "permission on the Google page.");
            }
        }
        String refresh = tokens.path("refresh_token").asText("");
        String token = tokens.path("access_token").asText("");
        if (refresh.isBlank() || token.isBlank()) {
            throw new GoogleException("Google returned incomplete credentials. Connect again.");
        }
        accounts.save(userId, service.provider(), "", secretJson(client[0], refresh));
        access.put(key(userId, service), new Access(token, expiry(tokens)));
    }

    /** A valid access token for this user and service; refreshed from the stored refresh token when needed. */
    public String accessToken(String userId, GoogleService service, boolean forceRefresh) {
        Access cached = access.get(key(userId, service));
        if (!forceRefresh && cached != null && Instant.now().plusSeconds(45).isBefore(cached.expires())) {
            return cached.token();
        }
        var account = accounts.find(userId, service.provider())
                .orElseThrow(() -> new GoogleException(service.label() + " is not connected for this Jarvis account. Ask Jarvis to connect "
                        + service.label() + "."));
        JsonNode secret = parse(account.secret(), service);
        String[] client = client();
        if (!client[0].equals(secret.path("clientId").asText())) {
            throw new GoogleException("The Google OAuth client on the server changed. Connect " + service.label() + " again.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("grant_type", "refresh_token");
        values.put("refresh_token", secret.path("refreshToken").asText());
        values.put("client_id", client[0]);
        if (!client[1].isBlank()) values.put("client_secret", client[1]);
        JsonNode tokens = tokenRequest(values, service);
        String token = tokens.path("access_token").asText("");
        if (token.isBlank()) {
            throw new GoogleException("Google returned no access token. Connect " + service.label() + " again.");
        }
        String rotated = tokens.path("refresh_token").asText("");
        if (!rotated.isBlank() && !rotated.equals(secret.path("refreshToken").asText())) {
            accounts.save(userId, service.provider(), account.accountEmail(), secretJson(client[0], rotated));
        }
        Access fresh = new Access(token, expiry(tokens));
        access.put(key(userId, service), fresh);
        return fresh.token();
    }

    /** Remembers the Google address of this user's connection (shown in status). */
    public void rememberEmail(String userId, GoogleService service, String email) {
        accounts.find(userId, service.provider()).ifPresent(account -> {
            if (email != null && !email.isBlank() && !email.equals(account.accountEmail())) {
                accounts.save(userId, service.provider(), email, account.secret());
            }
        });
    }

    /** Removes the user's connection of one service and revokes it at Google. */
    public Map<String, Object> disconnect(String userId, GoogleService service) {
        var account = accounts.find(userId, service.provider());
        accounts.delete(userId, service.provider());
        access.remove(key(userId, service));
        boolean revoked = false;
        if (account.isPresent()) {
            try {
                HttpResponse<String> response = http.send(HttpRequest.newBuilder(revoke).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token",
                                parse(account.get().secret(), service).path("refreshToken").asText()))))
                        .build(), HttpResponse.BodyHandlers.ofString());
                revoked = response.statusCode() == 200;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException ignored) {
                // The local connection is gone either way; the user can still revoke on the Google account page.
            }
        }
        return Map.of("connected", false, "revokedAtGoogle", revoked, "message", revoked
                ? service.label() + " disconnected and access revoked at Google."
                : service.label() + " disconnected in Jarvis. Also check https://myaccount.google.com/permissions to revoke Jarvis.");
    }

    private JsonNode tokenRequest(Map<String, String> values, GoogleService service) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(tokenUri).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(values))).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String error = "";
                try { error = JSON.readTree(response.body()).path("error").asText(""); } catch (Exception ignored) { }
                if (error.equals("invalid_grant")) {
                    throw new GoogleException("Google rejected the saved " + service.label() + " permission (invalid_grant): it was "
                            + "revoked, or the Google Cloud app is still in 'Testing' mode, where Google expires it after 7 days. "
                            + "Connect " + service.label() + " again; to stop the weekly expiry the administrator publishes the app "
                            + "(In production).");
                }
                throw new GoogleException("Google authorization failed (HTTP " + response.statusCode() + (error.isBlank() ? "" : ", " + error) + ").");
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GoogleException("Google authorization interrupted.");
        } catch (java.io.IOException exception) {
            throw new GoogleException("Cannot contact the Google authorization server.");
        }
    }

    private static String key(String userId, GoogleService service) {
        return userId + "|" + service.provider();
    }

    private static Instant expiry(JsonNode tokens) {
        return Instant.now().plusSeconds(Math.max(1, tokens.path("expires_in").asInt(3600)));
    }

    private static String secretJson(String clientId, String refresh) {
        try {
            return JSON.writeValueAsString(Map.of("clientId", clientId, "refreshToken", refresh));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static JsonNode parse(String json, GoogleService service) {
        try {
            return JSON.readTree(json);
        } catch (Exception exception) {
            throw new GoogleException("Saved " + service.label() + " connection is damaged. Connect it again.");
        }
    }

    static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
