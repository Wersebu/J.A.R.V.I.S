package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryRecord;

import java.util.List;

/**
 * Builds AI-ready user profile context from selected memories.
 */
public interface MemoryProfileBuilder {

    /**
     * Builds a structured user profile.
     *
     * @param memories selected memories
     * @return profile text
     */
    String buildProfile(List<MemoryRecord> memories);
}
