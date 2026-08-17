package com.jarvis.api.controller;

import com.jarvis.common.dto.ChatRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use handoff between the POST that submits a chat request body and the GET
 * call that opens its Server-Sent Events stream.
 *
 * <p>Server-Sent Events is GET-only by specification, but the request body (message text,
 * attachments) must never travel in a URL query string - a pasted document can easily exceed the
 * embedded servlet container's default max request-line/header size. The POST call holds the
 * request here just long enough for the client's immediately following GET to retrieve it by
 * token.</p>
 */
@Component
class PendingChatStreamStore {

    private static final Duration DEFAULT_TTL = Duration.ofSeconds(30);

    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    private record Entry(ChatRequest request, Instant expiresAt) {
    }

    PendingChatStreamStore() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    PendingChatStreamStore(Clock clock, Duration ttl) {
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Stores a request and returns a one-time token to retrieve it.
     *
     * @param request chat request to hold
     * @return correlation token
     */
    String store(ChatRequest request) {
        sweepExpired();
        String token = UUID.randomUUID().toString();
        pending.put(token, new Entry(request, clock.instant().plus(ttl)));
        return token;
    }

    /**
     * Consumes (removes) the request held under the given token, if still valid.
     *
     * @param token correlation token
     * @return the held request, or empty when the token is unknown, expired, or already consumed
     */
    Optional<ChatRequest> consume(String token) {
        Entry entry = pending.remove(token);
        if (entry == null || clock.instant().isAfter(entry.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(entry.request());
    }

    private void sweepExpired() {
        Instant now = clock.instant();
        pending.values().removeIf(entry -> now.isAfter(entry.expiresAt()));
    }
}
