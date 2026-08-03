package com.jarvis.knowledge;

import com.jarvis.common.event.KnowledgeEvent;

/**
 * Publishes knowledge engine lifecycle events.
 */
public interface KnowledgeEventPublisher {

    /**
     * Publishes a knowledge event.
     *
     * @param event event to publish
     */
    void publish(KnowledgeEvent event);
}
