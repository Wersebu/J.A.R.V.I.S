package com.jarvis.ollama;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates access to Ollama and gives interactive chat requests priority.
 */
@Service
public class OllamaRequestCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(OllamaRequestCoordinator.class);
    private static final long MEMORY_AGENT_PRIORITY_GRACE_MS = 200L;

    private final boolean chatPriority;
    private final CognitiveEventBus cognitiveEventBus;
    private AIJobType activeJob;
    private int waitingChatJobs;

    /**
     * Creates the Ollama request coordinator.
     *
     * @param chatPriority whether chat requests have priority over memory jobs
     */
    public OllamaRequestCoordinator(
            @Value("${jarvis.memory.background.chat-priority:true}") boolean chatPriority,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.chatPriority = chatPriority;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    /**
     * Acquires an Ollama permit for one job.
     *
     * @param jobType job type
     * @param requestId request id for logs
     * @return acquired permit
     */
    public Permit acquire(AIJobType jobType, String requestId) {
        long queuedNano = System.nanoTime();
        Instant queuedAt = Instant.now();
        AIJobType activeAtQueue;
        synchronized (this) {
            activeAtQueue = activeJob;
            if (jobType == AIJobType.CHAT) {
                waitingChatJobs++;
            }
            LOGGER.info("[JARVIS][requestId={}][OLLAMA] QUEUE_STARTED jobType={} activeJob={}",
                    requestId, jobType, activeAtQueue == null ? "NONE" : activeAtQueue);
            if (jobType == AIJobType.CHAT) {
                cognitiveEventBus.publish(CognitiveEventType.EXECUTION_TRACE, "STARTED", "Ollama queue wait started", null, Map.of(
                        "stage", "OLLAMA_QUEUE_WAIT",
                        "phase", "STARTED",
                        "durationMs", 0,
                        "queuePosition", activeAtQueue == null ? 0 : 1,
                        "activeJob", activeAtQueue == null ? "NONE" : activeAtQueue.name(),
                        "severity", "GREEN"
                ));
            }
            if (chatPriority && jobType == AIJobType.MEMORY_AGENT && activeJob == null && waitingChatJobs == 0) {
                LOGGER.info("[JARVIS][requestId={}][OLLAMA] WAITING_FOR_OLLAMA_PRIORITY graceMs={}",
                        requestId, MEMORY_AGENT_PRIORITY_GRACE_MS);
                waitQuietly(MEMORY_AGENT_PRIORITY_GRACE_MS);
            }
            while (activeJob != null || shouldPostpone(jobType)) {
                if (jobType == AIJobType.CHAT && activeJob == AIJobType.MEMORY_AGENT) {
                    LOGGER.info("[JARVIS][requestId={}][OLLAMA] CHAT_WAITING_BEHIND_MEMORY_AGENT", requestId);
                }
                if (jobType == AIJobType.MEMORY_AGENT && waitingChatJobs > 0) {
                    LOGGER.info("[JARVIS][requestId={}][OLLAMA] WAITING_FOR_OLLAMA_PRIORITY chatPending={}", requestId, waitingChatJobs);
                }
                waitQuietly();
            }
            if (jobType == AIJobType.CHAT) {
                waitingChatJobs--;
            }
            activeJob = jobType;
        }
        long waitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedNano);
        InferenceDiagnostics diagnostics = InferenceDiagnosticsContext.current();
        if (diagnostics != null) {
            diagnostics.setOllamaPermitQueueWaitMs(waitMs);
            diagnostics.setRequestWaitedForAnotherOllamaJob(activeAtQueue != null);
            diagnostics.setOllamaJobTypeBlockingRequest(activeAtQueue == null ? null : activeAtQueue.name());
        }
        LOGGER.info("[JARVIS][requestId={}][OLLAMA] QUEUE_ACQUIRED waitMs={} activeJobAtQueue={}",
                requestId, waitMs, activeAtQueue == null ? "NONE" : activeAtQueue);
        if (jobType == AIJobType.CHAT) {
            cognitiveEventBus.publish(CognitiveEventType.EXECUTION_TRACE, "FINISHED", "Ollama queue acquired", null, Map.of(
                    "stage", "OLLAMA_QUEUE_WAIT",
                    "phase", "FINISHED",
                    "durationMs", waitMs,
                    "queuePosition", activeAtQueue == null ? 0 : 1,
                    "activeJob", activeAtQueue == null ? "NONE" : activeAtQueue.name(),
                    "severity", severity(waitMs)
            ));
            if (waitMs > 500L) {
                cognitiveEventBus.publish(CognitiveEventType.EXECUTION_BOTTLENECK, "MEDIUM", "Potential Bottleneck", null, Map.of(
                        "stage", "OLLAMA_QUEUE_WAIT",
                        "durationMs", waitMs,
                        "reason", "Waiting for local model",
                        "severity", waitMs > 5_000L ? "HIGH" : "MEDIUM"
                ));
            }
        }
        return new Permit(this, jobType, queuedAt, Instant.now(), waitMs, activeAtQueue);
    }

    private String severity(long durationMs) {
        if (durationMs < 20L) {
            return "GREEN";
        }
        if (durationMs < 100L) {
            return "YELLOW";
        }
        if (durationMs < 500L) {
            return "ORANGE";
        }
        return "RED";
    }

    private boolean shouldPostpone(AIJobType jobType) {
        return chatPriority && jobType == AIJobType.MEMORY_AGENT && waitingChatJobs > 0;
    }

    private void waitQuietly() {
        try {
            wait();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Interrupted while waiting for Ollama permit", exception);
        }
    }

    private void waitQuietly(long millis) {
        try {
            wait(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OllamaException("Interrupted while waiting for Ollama permit", exception);
        }
    }

    private synchronized void release(AIJobType jobType) {
        if (activeJob == jobType) {
            activeJob = null;
        }
        notifyAll();
    }

    /**
     * Acquired Ollama permit.
     */
    public record Permit(
            OllamaRequestCoordinator coordinator,
            AIJobType jobType,
            Instant queuedAt,
            Instant startedAt,
            long queueWaitMs,
            AIJobType activeJobAtQueueTime
    ) implements AutoCloseable {

        @Override
        public void close() {
            coordinator.release(jobType);
        }
    }
}
