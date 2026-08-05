package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryCategory;

import java.util.List;
import java.util.Set;

/**
 * Normalized memory query used by retrieval components.
 *
 * @param original original query
 * @param tokens normalized tokens
 * @param preferredCategories inferred preferred memory categories
 */
public record MemoryQuery(
        String original,
        List<String> tokens,
        Set<MemoryCategory> preferredCategories
) {
}
