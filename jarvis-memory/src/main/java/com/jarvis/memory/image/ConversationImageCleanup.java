package com.jarvis.memory.image;

import com.jarvis.common.image.ConversationImageRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Periodically sweeps conversation-scoped image records whose retention window has elapsed from
 * {@code AVAILABLE} to {@code EXPIRED}. This only ever corrects the stored status label - the
 * physical file itself is exclusively the temporary workspace's own TTL cleanup's responsibility
 * (see {@code TemporaryWorkspaceCleanup}); this component never touches disk. Safe to run
 * regardless of restarts or how many records exist - a plain, idempotent bulk {@code UPDATE}.
 */
@Component
public class ConversationImageCleanup {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationImageCleanup.class);

    private final ConversationImageRegistry registry;
    private final ConversationImageProperties properties;

    /**
     * Creates the cleanup sweep.
     *
     * @param registry conversation image registry
     * @param properties conversation image configuration
     */
    public ConversationImageCleanup(ConversationImageRegistry registry, ConversationImageProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * Sweeps expired conversation images.
     */
    @Scheduled(fixedDelay = 300_000L, initialDelay = 60_000L)
    public void sweep() {
        if (!properties.enabled()) {
            return;
        }
        int expired = registry.expireOlderThan(Instant.now());
        if (expired > 0) {
            LOGGER.info("[CONVERSATION_IMAGES] periodic sweep expired={}", expired);
        }
    }
}
