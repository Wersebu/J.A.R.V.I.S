package com.jarvis.common.memory;

import java.util.List;

/**
 * AI-ready memory context built from retrieved cognitive memories.
 *
 * @param context formatted memory context
 * @param memories memories included in the context
 * @param memoryCount number of included memories
 * @param totalCharacters total context characters
 * @param estimatedTokens estimated token count
 * @param isEmpty whether no relevant memories were found
 */
public record CognitiveMemoryContext(
        String context,
        List<MemoryRecord> memories,
        int memoryCount,
        int totalCharacters,
        int estimatedTokens,
        boolean isEmpty
) {

    /**
     * Creates an empty memory context.
     *
     * @return empty context
     */
    public static CognitiveMemoryContext empty() {
        return new CognitiveMemoryContext("", List.of(), 0, 0, 0, true);
    }

    /**
     * Creates a normalized memory context.
     *
     * @param context formatted context
     * @param memories source memories
     */
    public CognitiveMemoryContext {
        memories = memories == null ? List.of() : List.copyOf(memories);
        context = context == null ? "" : context;
    }
}
