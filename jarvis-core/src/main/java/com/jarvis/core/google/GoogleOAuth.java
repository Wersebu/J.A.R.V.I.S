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
import java.util.Set;
import com.jarvis.common.integration.*;
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
    public record Authorization(GoogleService service, String url, String state, String redirectUri, String verifier, Instant deadline,List<String> requiredScopes) {
        public Authorization(GoogleService service,String url,String state,String redirectUri,String verifier,Instant deadline){this(service,url,state,redirectUri,verifier,deadline,service.scopes());}
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
    private final Map<String,IntegrationState> failures=new ConcurrentHashMap<>();
    private final Object[] credentialLocks=java.util.stream.IntStream.range(0,64).mapToObj(i->new Object()).toArray();
    private Object credentialLock(String user,GoogleService service){return credentialLocks[Math.floorMod(key(user,service).hashCode(),credentialLocks.length)];}
    private final java.util.LinkedHashSet<String> completedAuthorizations=new java.util.LinkedHashSet<>();
    private final Map<String,String> authorizationOwners=java.util.Collections.synchronizedMap(new LinkedHashMap<>());


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

    public String authorizationRevision(String user,GoogleService service){return accounts.find(user,service.provider()).map(a->parse(a.secret(),service).path("authorizationRevision").asText("")).orElse("");}
    public Set<String> grantedScopes(String userId,GoogleService service){return accounts.find(userId,service.provider()).map(a->{var secret=parse(a.secret(),service);var result=new java.util.HashSet<String>();for(var value:secret.path("grantedScopes"))result.add(value.asText());return Set.copyOf(result);}).orElse(Set.of());}
    public IntegrationStatus operationStatus(String userId,GoogleService service,String operation){
        var account=accounts.find(userId,service.provider());Set<String> required=service.requiredScopes(operation),granted=grantedScopes(userId,service);
        boolean known=account.isPresent()&&parse(account.get().secret(),service).path("scopesKnown").asBoolean(false);
        var missing=new java.util.HashSet<String>();if(known)for(String scope:required)if(!GoogleService.permits(granted,scope))missing.add(scope);
        Access cached=access.get(key(userId,service));
        IntegrationState state=account.isEmpty()?IntegrationState.DISCONNECTED:!missing.isEmpty()?IntegrationState.MISSING_SCOPE:cached==null?IntegrationState.REFRESH_REQUIRED:Instant.now().plusSeconds(45).isBefore(cached.expires())?IntegrationState.CONNECTED:IntegrationState.REFRESH_REQUIRED;
        state=failures.getOrDefault(key(userId,service),state);
        return new IntegrationStatus(service.provider(),operation,state,account.isPresent(),known,granted,required,missing,state.name(),Instant.now());
    }
    public void requireScopes(String user,GoogleService service,Set<String> required){
        accessToken(user,service,false);var secret=parse(accounts.find(user,service.provider()).orElseThrow().secret(),service);
        if(secret.path("scopesKnown").asBoolean(false)){Set<String> granted=grantedScopes(user,service);if(required.stream().anyMatch(scope->!GoogleService.permits(granted,scope)))throw new GoogleException("MISSING_SCOPE","Brakuje uprawnień Google wymaganych przez tę operację.");}
    }
    public Map<String, Object> status(String userId, GoogleService service) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configured());
        var account = accounts.find(userId, service.provider());
        result.put("connected", account.isPresent());
        result.put("email", account.map(ConnectedAccountRepository.ConnectedAccount::accountEmail).orElse(""));
        var permissions=operationStatus(userId,service,"READ");result.put("state",permissions.state().name());result.put("scopesKnown",permissions.scopesKnown());result.put("grantedScopes",permissions.grantedScopes());result.put("requiredScopes",permissions.requiredScopes());result.put("missingScopes",permissions.missingScopes());
        return result;
    }

    /** Starts consent for one service with a loopback redirect on the user's device. */
    public Authorization begin(GoogleService service) {return begin(null,service,service.requiredScopes("READ"));}
    public Authorization begin(String userId,GoogleService service,Set<String> required) {
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
        query.put("scope", String.join(" ", required));
        query.put("include_granted_scopes", "true");
        query.put("state", state);
        query.put("code_challenge_method", "S256");
        query.put("code_challenge", challenge(verifier));
        // Offline access permits silent refresh; incremental consent retains existing grants.
        query.put("access_type", "offline");
        if(userId==null||accounts.find(userId,service.provider()).isEmpty())query.put("prompt", "consent");
        if(userId!=null)synchronized(authorizationOwners){authorizationOwners.put(state,userId);while(authorizationOwners.size()>2048)authorizationOwners.remove(authorizationOwners.keySet().iterator().next());}
        return new Authorization(service, authorize + "?" + form(query), state, redirect, verifier, Instant.now().plusSeconds(300),List.copyOf(required));
    }

    /** Exchanges the code from the consent page and stores the user's encrypted refresh token for that service. */
    public void complete(String userId,Authorization authorization,String code){synchronized(credentialLock(userId,authorization.service())){completeLocked(userId,authorization,code);}}
    private void completeLocked(String userId, Authorization authorization, String code) {
        synchronized(authorizationOwners){if(completedAuthorizations.contains(authorization.state()))throw new GoogleException("AUTHORIZATION_ALREADY_COMPLETED","Ta odpowiedź autoryzacji została już obsłużona.");String owner=authorizationOwners.get(authorization.state());if(owner!=null&&!owner.equals(userId))throw new GoogleException("AUTHORIZATION_FAILED","Autoryzacja należy do innego użytkownika.");authorizationOwners.remove(authorization.state());completedAuthorizations.add(authorization.state());while(completedAuthorizations.size()>2048)completedAuthorizations.remove(completedAuthorizations.iterator().next());}
        GoogleService service = authorization.service();
        if (Instant.now().isAfter(authorization.deadline())) {
            throw new GoogleException("AUTHORIZATION_TIMEOUT","Upłynął czas autoryzacji Google. Ponów połączenie, jeśli nadal jest potrzebne.");
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
        Set<String> granted=scopes(tokens);
        if(tokens.has("scope")&&authorization.requiredScopes().stream().anyMatch(scope->!GoogleService.permits(granted,scope)))throw new GoogleException("MISSING_SCOPE","Nie przyznano wymaganych uprawnień Google. Pozostałe części polecenia mogą być kontynuowane.");
        var existing=accounts.find(userId,service.provider());
        String refresh=tokens.path("refresh_token").asText("");String token=tokens.path("access_token").asText("");
        if(refresh.isBlank()&&existing.isPresent()){var old=parse(existing.get().secret(),service);if(client[0].equals(old.path("clientId").asText()))refresh=old.path("refreshToken").asText("");}
        if(refresh.isBlank()||token.isBlank())throw new GoogleException("AUTHORIZATION_REQUIRED","Google nie zwrócił pełnych danych połączenia. Dotychczasowe połączenie nie zostało nadpisane.");
        JsonNode old=existing.map(account->parse(account.secret(),service)).orElse(JSON.createObjectNode());
        accounts.save(userId,service.provider(),existing.map(ConnectedAccountRepository.ConnectedAccount::accountEmail).orElse(""),secretJson(client[0],refresh,tokens,old,true));
        access.put(key(userId, service), new Access(token, expiry(tokens)));failures.remove(key(userId,service));
    }

    /** A valid access token for this user and service; refreshed from the stored refresh token when needed. */
    public String accessToken(String userId,GoogleService service,boolean forceRefresh){synchronized(credentialLock(userId,service)){return accessTokenLocked(userId,service,forceRefresh);}}
    private String accessTokenLocked(String userId, GoogleService service, boolean forceRefresh) {
        Access cached = access.get(key(userId, service));
        if (!forceRefresh && cached != null && Instant.now().plusSeconds(45).isBefore(cached.expires())) {
            return cached.token();
        }
        var account = accounts.find(userId, service.provider())
                .orElseThrow(() -> new GoogleException("DISCONNECTED",service.label() + " is not connected for this Jarvis account. Ask Jarvis to connect "
                        + service.label() + "."));
        JsonNode secret = parse(account.secret(), service);
        String[] client = client();
        if (!client[0].equals(secret.path("clientId").asText())) {
            throw new GoogleException("AUTHORIZATION_REQUIRED","The Google OAuth client on the server changed. Connect " + service.label() + " again.");
        }
        Map<String, String> values = new LinkedHashMap<>();
        values.put("grant_type", "refresh_token");
        values.put("refresh_token", secret.path("refreshToken").asText());
        values.put("client_id", client[0]);
        if (!client[1].isBlank()) values.put("client_secret", client[1]);
        JsonNode tokens;
        try{tokens=tokenRequest(values,service);}catch(GoogleException failure){access.remove(key(userId,service));failures.put(key(userId,service),failure.authorizationRequired()?IntegrationState.AUTHORIZATION_REQUIRED:failure.code().equals("TEMPORARILY_UNAVAILABLE")?IntegrationState.TEMPORARILY_UNAVAILABLE:IntegrationState.ERROR);throw failure;}
        String token = tokens.path("access_token").asText("");
        if (token.isBlank()) {
            throw new GoogleException("AUTHORIZATION_REQUIRED","Google returned no access token. Connect " + service.label() + " again.");
        }
        String rotated=tokens.path("refresh_token").asText("");
        accounts.save(userId,service.provider(),account.accountEmail(),secretJson(client[0],rotated.isBlank()?secret.path("refreshToken").asText():rotated,tokens,secret));
        Access fresh = new Access(token, expiry(tokens));
        access.put(key(userId, service), fresh);failures.remove(key(userId,service));
        return fresh.token();
    }

    public String rejectedAccessToken(String user,GoogleService service,String rejected){synchronized(credentialLock(user,service)){var cached=access.get(key(user,service));if(cached!=null&&!cached.token().equals(rejected)&&Instant.now().plusSeconds(45).isBefore(cached.expires()))return cached.token();return accessTokenLocked(user,service,true);}}

    /** Remembers the Google address of this user's connection (shown in status). */
    public void rememberEmail(String userId,GoogleService service,String email){synchronized(credentialLock(userId,service)){rememberEmailLocked(userId,service,email);}}
    private void rememberEmailLocked(String userId, GoogleService service, String email) {
        accounts.find(userId, service.provider()).ifPresent(account -> {
            if (email != null && !email.isBlank() && !email.equals(account.accountEmail())) {
                accounts.save(userId, service.provider(), email, account.secret());
            }
        });
    }

    /** Removes the user's connection of one service and revokes it at Google. */
    public Map<String,Object> disconnect(String userId,GoogleService service){synchronized(credentialLock(userId,service)){return disconnectLocked(userId,service);}}
    private Map<String, Object> disconnectLocked(String userId, GoogleService service) {
        var account = accounts.find(userId, service.provider());
        accounts.delete(userId, service.provider());
        access.remove(key(userId, service));failures.remove(key(userId,service));
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
                    throw new GoogleException("AUTHORIZATION_REQUIRED","Google rejected the saved " + service.label() + " permission (invalid_grant): it was "
                            + "revoked, or the Google Cloud app is still in 'Testing' mode, where Google expires it after 7 days. "
                            + "Connect " + service.label() + " again; to stop the weekly expiry the administrator publishes the app "
                            + "(In production).");
                }
                throw new GoogleException(response.statusCode()==429||response.statusCode()>=500?"TEMPORARILY_UNAVAILABLE":"ERROR","Nie udało się odświeżyć połączenia Google (HTTP "+response.statusCode()+").");
            }
            return JSON.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GoogleException("CANCELLED","Przerwano autoryzację Google.");
        } catch (java.io.IOException exception) {
            throw new GoogleException("TEMPORARILY_UNAVAILABLE","Serwer autoryzacji Google jest chwilowo niedostępny.");
        }
    }

    private static String key(String userId, GoogleService service) {
        return userId + "|" + service.provider();
    }

    private static Instant expiry(JsonNode tokens) {
        return Instant.now().plusSeconds(Math.max(1, tokens.path("expires_in").asInt(3600)));
    }

    private static Set<String> scopes(JsonNode tokens){var result=new java.util.HashSet<String>();for(String value:tokens.path("scope").asText("").split("\\s+"))if(!value.isBlank())result.add(value);return Set.copyOf(result);}
    private static String secretJson(String clientId,String refresh,JsonNode tokens,JsonNode previous){return secretJson(clientId,refresh,tokens,previous,false);}
    private static String secretJson(String clientId,String refresh,JsonNode tokens,JsonNode previous,boolean newAuthorization){
        try{var value=new LinkedHashMap<String,Object>();value.put("clientId",clientId);value.put("refreshToken",refresh);value.put("authorizationRevision",newAuthorization?java.util.UUID.randomUUID().toString():previous.path("authorizationRevision").asText(""));
            boolean known=tokens.has("scope")||previous.path("scopesKnown").asBoolean(false);value.put("scopesKnown",known);
            if(tokens.has("scope"))value.put("grantedScopes",scopes(tokens));else{var retained=new java.util.HashSet<String>();for(var scope:previous.path("grantedScopes"))retained.add(scope.asText());value.put("grantedScopes",retained);}
            return JSON.writeValueAsString(value);
        }catch(Exception e){throw new IllegalStateException("Cannot persist Google credential metadata");}
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
