package com.jarvis.memory.cognitive;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the cognitive memory engine.
 *
 * @param databasePath SQLite database path
 * @param workingHistoryLength maximum working memory messages per conversation
 */
@ConfigurationProperties(prefix = "jarvis.memory")
public record MemoryProperties(String databasePath, int workingHistoryLength) {

    /**
     * Creates memory properties with safe defaults.
     *
     * @param databasePath SQLite database path
     * @param workingHistoryLength maximum working memory messages per conversation
     */
    public MemoryProperties {
        databasePath = databasePath == null || databasePath.isBlank() ? "./data/jarvis-memory.db" : databasePath;
        workingHistoryLength = workingHistoryLength <= 0 ? 20 : workingHistoryLength;
    }
}
