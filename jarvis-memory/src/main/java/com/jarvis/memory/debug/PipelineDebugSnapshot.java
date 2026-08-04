package com.jarvis.memory.debug;

import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.common.context.KnowledgeSource;
import com.jarvis.common.memory.MemoryRecord;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Debug snapshot of the latest cognitive pipeline execution.
 *
 * @param conversationId conversation identifier
 * @param currentPipeline completed pipeline stage names
 * @param retrievedMemories memories retrieved by memory retrieval
 * @param injectedMemories memories injected into the prompt
 * @param knowledgeSources knowledge sources injected into the prompt
 * @param promptStatistics prompt statistics
 * @param selectedModel selected model
 * @param executionPlan execution plan
 * @param response latest response
 * @param updatedAt snapshot timestamp
 */
public record PipelineDebugSnapshot(
        String conversationId,
        List<String> currentPipeline,
        List<MemoryRecord> retrievedMemories,
        List<MemoryRecord> injectedMemories,
        List<KnowledgeSource> knowledgeSources,
        Map<String, Object> promptStatistics,
        String selectedModel,
        ExecutionPlan executionPlan,
        String response,
        Instant updatedAt
) {

    /**
     * Creates an empty debug snapshot.
     *
     * @return empty snapshot
     */
    public static PipelineDebugSnapshot empty() {
        return new PipelineDebugSnapshot("", List.of(), List.of(), List.of(), List.of(), Map.of(), "", null, "", Instant.EPOCH);
    }
}
