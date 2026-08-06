package com.jarvis.memory.pipeline;

import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.memory.cognitive.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Marks legacy semantic memory as inactive while preserving the pipeline slot for future memory sources.
 */
@Service
@Order(25)
public class MemoryRetrievalStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryRetrievalStage.class);

    private final MemoryProperties memoryProperties;

    /**
     * Creates the memory retrieval stage.
     *
     * @param memoryProperties memory configuration
     */
    public MemoryRetrievalStage(MemoryProperties memoryProperties) {
        this.memoryProperties = memoryProperties;
    }

    @Override
    public String name() {
        return "MemoryRetrievalStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        LOGGER.info("[JARVIS][LONG_TERM_MEMORY] backend=KNOWLEDGE_FILES semanticSqlEnabled={} legacyRetrievalEnabled={}",
                memoryProperties.semanticSql().enabled(),
                memoryProperties.legacy().retrievalEnabled());
        LOGGER.info("[JARVIS][SEMANTIC_SQL_MEMORY] retrievalEnabled=false writesEnabled=false keepTables={}",
                memoryProperties.legacy().keepTables());
        return context.withMemoryContext(CognitiveMemoryContext.empty())
                .withMetadata("memoryRetrievalTimeMs", 0L)
                .withMetadata("memoriesUsed", 0)
                .withMetadata("longTermMemoryBackend", "KNOWLEDGE_FILES")
                .withMetadata("semanticSqlMemoryRetrieval", false);
    }
}
