package com.jarvis.memory.job;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.memory.agent.MemoryAgentService;
import com.jarvis.memory.cognitive.MemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Queue-backed background memory job service.
 */
@Service
public class DefaultMemoryJobService implements MemoryJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultMemoryJobService.class);

    private final MemoryAgentService memoryAgentService;
    private final CognitiveEventBus cognitiveEventBus;
    private final MemoryProperties properties;
    private final ThreadPoolExecutor executor;

    /**
     * Creates the memory job service.
     *
     * @param memoryAgentService memory agent service
     * @param cognitiveEventBus cognitive event bus
     * @param properties memory properties
     */
    public DefaultMemoryJobService(
            MemoryAgentService memoryAgentService,
            CognitiveEventBus cognitiveEventBus,
            MemoryProperties properties
    ) {
        this.memoryAgentService = memoryAgentService;
        this.cognitiveEventBus = cognitiveEventBus;
        this.properties = properties;
        int threads = properties.background().executorThreads();
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.background().queueCapacity()),
                runnable -> {
                    Thread thread = new Thread(runnable, "jarvis-memory-job");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public boolean submit(com.jarvis.memory.pipeline.PipelineContext context) {
        if (!properties.background().enabled() || !properties.backgroundAgent().enabled()) {
            LOGGER.info("[JARVIS][MEMORY][sourceRequestId={}] JOB_SKIPPED backgroundEnabled={} backgroundAgentEnabled={} durableMemory=KNOWLEDGE_FILES",
                    context.requestId(), properties.background().enabled(), properties.backgroundAgent().enabled());
            return false;
        }
        MemoryJob job = new MemoryJob(
                UUID.randomUUID(),
                context.requestId(),
                context.conversationId(),
                context.request().message(),
                context.response(),
                Instant.now(),
                context.memoryContext().memories()
        );
        try {
            executor.execute(() -> run(job));
            publish(job, CognitiveEventType.MEMORY_JOB_QUEUED, "QUEUED", "Memory job queued", Map.of(
                    "queueSize", executor.getQueue().size(),
                    "memoryJobId", job.memoryJobId().toString()
            ));
            LOGGER.info("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] JOB_QUEUED queueSize={}",
                    job.memoryJobId(), job.sourceRequestId(), executor.getQueue().size());
            return true;
        } catch (RuntimeException exception) {
            if (properties.background().skipWhenQueueFull()) {
                LOGGER.warn("[JARVIS][MEMORY][sourceRequestId={}] JOB_SKIPPED queueFull=true", context.requestId());
                return false;
            }
            publish(job, CognitiveEventType.MEMORY_AGENT_ERROR, "ERROR", "Memory job queue rejected", Map.of(
                    "memoryJobId", job.memoryJobId().toString(),
                    "error", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            LOGGER.error("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] JOB_REJECTED",
                    job.memoryJobId(), job.sourceRequestId(), exception);
            return false;
        }
    }

    private void run(MemoryJob job) {
        try {
            memoryAgentService.analyze(job);
        } catch (RuntimeException exception) {
            publish(job, CognitiveEventType.MEMORY_AGENT_ERROR, "ERROR", "Memory Agent failed", Map.of(
                    "memoryJobId", job.memoryJobId().toString(),
                    "error", exception.getMessage() == null ? "" : exception.getMessage()
            ));
            LOGGER.error("[JARVIS][MEMORY][jobId={}][sourceRequestId={}] MEMORY_AGENT_ERROR",
                    job.memoryJobId(), job.sourceRequestId(), exception);
        }
    }

    private void publish(MemoryJob job, CognitiveEventType type, String status, String message, Map<String, Object> metadata) {
        cognitiveEventBus.publishBackground(
                job.sourceRequestId(),
                job.conversationId(),
                type,
                status,
                message,
                "memory:" + job.memoryJobId(),
                metadata
        );
    }
}
