package com.jarvis.memory.cognitive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the cognitive memory engine.
 *
 * @param databasePath SQLite database path
 * @param workingHistoryLength maximum working memory messages per conversation
 * @param semanticSql legacy semantic SQLite configuration
 * @param backgroundAgent background semantic memory agent configuration
 * @param legacy legacy memory access configuration
 * @param background background memory processing configuration
 */
@ConfigurationProperties(prefix = "jarvis.memory")
public record MemoryProperties(
        String databasePath,
        int workingHistoryLength,
        SemanticSql semanticSql,
        BackgroundAgent backgroundAgent,
        Legacy legacy,
        Background background
) {

    /**
     * Creates memory properties with safe defaults.
     *
     * @param databasePath SQLite database path
     * @param workingHistoryLength maximum working memory messages per conversation
     */
    public MemoryProperties {
        databasePath = databasePath == null || databasePath.isBlank() ? "./data/jarvis-memory.db" : databasePath;
        workingHistoryLength = workingHistoryLength <= 0 ? 20 : workingHistoryLength;
        semanticSql = semanticSql == null ? new SemanticSql(false) : semanticSql;
        backgroundAgent = backgroundAgent == null ? new BackgroundAgent(false) : backgroundAgent;
        legacy = legacy == null ? new Legacy(false, false, true) : legacy;
        background = background == null ? new Background(false, 1, 100, true, false) : background;
    }

    /**
     * Legacy semantic SQLite memory configuration.
     *
     * @param enabled whether semantic SQLite is an active long-term memory source
     */
    public record SemanticSql(boolean enabled) {
    }

    /**
     * Background semantic memory agent configuration.
     *
     * @param enabled whether completed conversations are analyzed into durable memory
     */
    public record BackgroundAgent(boolean enabled) {
    }

    /**
     * Legacy memory table access configuration.
     *
     * @param retrievalEnabled whether legacy semantic retrieval is active
     * @param writesEnabled whether legacy semantic writes are active
     * @param keepTables whether legacy tables are retained
     */
    public record Legacy(boolean retrievalEnabled, boolean writesEnabled, boolean keepTables) {
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
