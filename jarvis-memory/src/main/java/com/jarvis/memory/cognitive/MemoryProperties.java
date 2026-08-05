package com.jarvis.memory.cognitive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the cognitive memory engine.
 *
 * @param databasePath SQLite database path
 * @param workingHistoryLength maximum working memory messages per conversation
 * @param background background memory processing configuration
 */
@ConfigurationProperties(prefix = "jarvis.memory")
public record MemoryProperties(String databasePath, int workingHistoryLength, Background background) {

    /**
     * Creates memory properties with safe defaults.
     *
     * @param databasePath SQLite database path
     * @param workingHistoryLength maximum working memory messages per conversation
     */
    public MemoryProperties {
        databasePath = databasePath == null || databasePath.isBlank() ? "./data/jarvis-memory.db" : databasePath;
        workingHistoryLength = workingHistoryLength <= 0 ? 20 : workingHistoryLength;
        background = background == null ? new Background(true, 1, 100, true, false) : background;
    }

    /**
     * Background memory job configuration.
     *
     * @param enabled whether background memory jobs are enabled
     * @param executorThreads executor thread count
     * @param queueCapacity maximum queued memory jobs
     * @param chatPriority whether chat requests have priority over memory jobs
     * @param skipWhenQueueFull whether jobs should be skipped when the queue is full
     */
    public record Background(
            boolean enabled,
            int executorThreads,
            int queueCapacity,
            boolean chatPriority,
            boolean skipWhenQueueFull
    ) {

        /**
         * Creates validated background configuration.
         */
        public Background {
            executorThreads = executorThreads <= 0 ? 1 : executorThreads;
            queueCapacity = queueCapacity <= 0 ? 100 : queueCapacity;
        }
    }
}
