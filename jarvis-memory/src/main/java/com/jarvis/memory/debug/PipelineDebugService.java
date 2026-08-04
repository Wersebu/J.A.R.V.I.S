package com.jarvis.memory.debug;

import com.jarvis.memory.pipeline.PipelineContext;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Stores the latest cognitive pipeline snapshot for debugging.
 */
@Service
public class PipelineDebugService {

    private volatile PipelineDebugSnapshot latest = PipelineDebugSnapshot.empty();

    /**
     * Updates the latest debug snapshot.
     *
     * @param context pipeline context
     */
    public void update(PipelineContext context) {
        boolean memoryInjected = context.prompt() != null && context.prompt().contains("COGNITIVE MEMORY");
        latest = new PipelineDebugSnapshot(
                context.conversationId(),
                context.metrics().keySet().stream().toList(),
                context.memoryContext().memories(),
                memoryInjected ? context.memoryContext().memories() : java.util.List.of(),
                context.knowledgeContext().sources(),
                Map.of(
                        "promptCharacters", context.prompt() == null ? 0 : context.prompt().length(),
                        "estimatedPromptTokens", context.metadata().getOrDefault("estimatedPromptTokens", 0),
                        "memoryCharacters", context.memoryContext().totalCharacters(),
                        "memoryEstimatedTokens", context.memoryContext().estimatedTokens(),
                        "knowledgeCharacters", context.knowledgeContext().totalCharacters(),
                        "knowledgeEstimatedTokens", context.knowledgeContext().estimatedTokens()
                ),
                context.model(),
                context.executionPlan(),
                context.response(),
                Instant.now()
        );
    }

    /**
     * Returns the latest debug snapshot.
     *
     * @return latest snapshot
     */
    public PipelineDebugSnapshot latest() {
        return latest;
    }
}
