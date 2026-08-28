package com.jarvis.core.moderation;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small isolated limiter for moderation so bursts cannot occupy the chat pipeline.
 */
@Component
public class TopkiMcModerationLimiter {

    private final TopkiMcModerationProperties properties;
    private final Clock clock;
    private final Semaphore parallel;
    private final AtomicInteger queued = new AtomicInteger();
    private final Map<String, ArrayDeque<Instant>> requestTimesByKey = new HashMap<>();

    @Autowired
    public TopkiMcModerationLimiter(TopkiMcModerationProperties properties) {
        this(properties, Clock.systemUTC());
    }

    TopkiMcModerationLimiter(TopkiMcModerationProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.parallel = new Semaphore(Math.max(1, properties.getMaxParallel()));
    }

    public Permit acquire(String keyId) {
        if (!allowRate(keyId)) {
            throw new ModerationOverloadException("RATE_LIMITED");
        }
        int queuePosition = queued.incrementAndGet();
        if (queuePosition > Math.max(0, properties.getMaxQueue()) + Math.max(1, properties.getMaxParallel())) {
            queued.decrementAndGet();
            throw new ModerationOverloadException("QUEUE_FULL");
        }
        boolean acquired = false;
        try {
            acquired = parallel.tryAcquire(properties.getQueueTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ModerationOverloadException("QUEUE_TIMEOUT");
            }
            return new Permit(parallel, queued);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ModerationOverloadException("INTERRUPTED");
        } finally {
            if (!acquired) {
                queued.decrementAndGet();
            }
        }
    }

    public int queued() {
        return queued.get();
    }

    public int active() {
        return Math.max(0, properties.getMaxParallel() - parallel.availablePermits());
    }

    private synchronized boolean allowRate(String keyId) {
        String safeKey = keyId == null || keyId.isBlank() ? "unknown" : keyId;
        Instant now = clock.instant();
        Instant cutoff = now.minus(Duration.ofMinutes(1));
        ArrayDeque<Instant> times = requestTimesByKey.computeIfAbsent(safeKey, ignored -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.removeFirst();
        }
        if (times.size() >= Math.max(1, properties.getRequestsPerMinute())) {
            return false;
        }
        times.addLast(now);
        return true;
    }

    public record Permit(Semaphore semaphore, AtomicInteger queued) implements AutoCloseable {
        @Override
        public void close() {
            semaphore.release();
            queued.decrementAndGet();
        }
    }

    public static class ModerationOverloadException extends RuntimeException {
        public ModerationOverloadException(String reasonCode) {
            super(reasonCode);
        }
    }
}
