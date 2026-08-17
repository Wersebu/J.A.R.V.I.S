package com.jarvis.api.controller;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.knowledge.KnowledgeMode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the POST-then-GET chat stream handoff store. This is the piece that lets
 * a long message travel in a POST body instead of a URL query string, which previously blew past
 * the embedded servlet container's default max request-line size for anything longer than a few
 * KB and made large messages silently fail to stream.
 */
class PendingChatStreamStoreTest {

    @Test
    void tokenResolvesToTheStoredRequestExactlyOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PendingChatStreamStore store = new PendingChatStreamStore(clock, Duration.ofSeconds(30));
        ChatRequest request = new ChatRequest("conversation-1", "a very long pasted message", null, KnowledgeMode.AUTO);

        String token = store.store(request);
        Optional<ChatRequest> resolved = store.consume(token);

        assertThat(resolved).contains(request);
        assertThat(store.consume(token)).isEmpty();
    }

    @Test
    void unknownTokenResolvesToEmpty() {
        PendingChatStreamStore store = new PendingChatStreamStore(Clock.systemUTC(), Duration.ofSeconds(30));

        assertThat(store.consume("does-not-exist")).isEmpty();
    }

    @Test
    void expiredTokenResolvesToEmpty() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        PendingChatStreamStore store = new PendingChatStreamStore(clock, Duration.ofSeconds(5));
        ChatRequest request = new ChatRequest("conversation-1", "hello", null, KnowledgeMode.AUTO);

        String token = store.store(request);
        clock.advance(Duration.ofSeconds(6));

        assertThat(store.consume(token)).isEmpty();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
