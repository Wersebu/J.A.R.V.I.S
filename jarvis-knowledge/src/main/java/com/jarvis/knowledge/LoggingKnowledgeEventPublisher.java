package com.jarvis.knowledge;

import com.jarvis.common.event.KnowledgeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Logs knowledge engine lifecycle events.
 */
@Service
public class LoggingKnowledgeEventPublisher implements KnowledgeEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingKnowledgeEventPublisher.class);

    /**
     * Publishes a knowledge event to the application log.
     *
     * @param event event to publish
     */
    @Override
    public void publish(KnowledgeEvent event) {
        LOGGER.info("[JARVIS] Knowledge event={} documentId={} path={}",
                event.type(),
                event.documentId(),
                event.relativePath());
    }
}
