package com.jarvis.knowledge.vault;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which vault sources the model actually received (search fragments, document and
 * workflow reads) per conversation, so the Windows client can show where an answer came from.
 * In-memory and bounded; it is diagnostic data, not conversation history.
 */
public final class VaultSourceTracker {

    private static final int MAX_CONVERSATIONS = 200;
    private static final int MAX_PER_CONVERSATION = 100;

    private final Map<String, Deque<SourceUse>> byConversation = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<SourceUse>> eldest) {
            return size() > MAX_CONVERSATIONS;
        }
    };

    /**
     * Records a source given to the model.
     *
     * @param use source use
     */
    public synchronized void record(SourceUse use) {
        String key = use.conversationId() == null || use.conversationId().isBlank() ? "-" : use.conversationId();
        Deque<SourceUse> uses = byConversation.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        uses.addFirst(use);
        while (uses.size() > MAX_PER_CONVERSATION) {
            uses.removeLast();
        }
    }

    /**
     * Returns sources of one conversation, newest first.
     *
     * @param conversationId conversation id
     * @param limit maximum entries
     * @return sources
     */
    public synchronized List<SourceUse> forConversation(String conversationId, int limit) {
        Deque<SourceUse> uses = byConversation.get(conversationId == null || conversationId.isBlank() ? "-" : conversationId);
        if (uses == null) {
            return List.of();
        }
        return new ArrayList<>(uses).subList(0, Math.min(uses.size(), Math.max(1, limit)));
    }

    /**
     * Source handed to the model.
     *
     * @param conversationId conversation
     * @param requestId request
     * @param kind {@code search}, {@code read} or {@code workflow}
     * @param query query for searches
     * @param path document path
     * @param title document title
     * @param heading heading breadcrumb
     * @param startLine first line
     * @param endLine last line
     * @param at timestamp
     */
    public record SourceUse(String conversationId, String requestId, String kind, String query, String path, String title,
                            String heading, int startLine, int endLine, String at) {
        /**
         * Creates a source use stamped now.
         */
        public static SourceUse now(String conversationId, String requestId, String kind, String query, String path, String title,
                                    String heading, int startLine, int endLine) {
            return new SourceUse(conversationId, requestId, kind, query, path, title, heading, startLine, endLine, Instant.now().toString());
        }
    }
}
