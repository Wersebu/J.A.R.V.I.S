package com.jarvis.ollama;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.InferencePriorityContext;
import com.jarvis.common.event.CognitiveEventBus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single Model Gateway admission gate for the configured Ollama endpoint. Never nest a second permit. */
@Service
public class OllamaRequestCoordinator implements com.jarvis.common.ai.ModelGateway {
    private final int concurrency, reservedInteractive, maxWaiting;
    private final long minimumFreeHeapBytes, queueTimeoutMillis;
    private final boolean chatPriority;
    private final CognitiveEventBus eventBus;
    private final Map<UUID,AIJobType> active=new HashMap<>();
    private int waiting, waitingInteractive;
    private int contextTokens=16384;private long contextTokenBudget;
    @Autowired(required=false)
    public void contextBudget(@Value("${jarvis.ai.context-window:16384}") int contextTokens,
        @Value("${jarvis.model-gateway.context-token-budget:0}") long budget) {
        if(contextTokens<1 || budget<0 || budget>0 && budget<contextTokens)throw new IllegalArgumentException("Context token budget cannot admit one request");
        this.contextTokens=contextTokens;this.contextTokenBudget=budget;
    }

    public OllamaRequestCoordinator(boolean chatPriority,CognitiveEventBus bus) {
        this(chatPriority,bus,1,0,128,0,300000);
    }
    @Autowired
    public OllamaRequestCoordinator(
        @Value("${jarvis.memory.background.chat-priority:true}") boolean chatPriority,
        CognitiveEventBus bus,
        @Value("${jarvis.model-gateway.concurrency:1}") int concurrency,
        @Value("${jarvis.model-gateway.reserved-interactive:1}") int reserved,
        @Value("${jarvis.model-gateway.max-waiting:128}") int maxWaiting,
        @Value("${jarvis.model-gateway.minimum-free-heap-bytes:0}") long minimumFreeHeapBytes,
        @Value("${jarvis.model-gateway.queue-timeout-millis:300000}") long queueTimeoutMillis) {
        if(concurrency<1 || concurrency>64 || reserved<0 || reserved>concurrency || reserved>=concurrency && concurrency>1
            || maxWaiting<1 || minimumFreeHeapBytes<0 || queueTimeoutMillis<1)
            throw new IllegalArgumentException("Invalid Model Gateway admission configuration");
        this.eventBus=bus;this.chatPriority=chatPriority;this.concurrency=concurrency;
        this.reservedInteractive=concurrency==1?0:reserved;this.maxWaiting=maxWaiting;
        this.minimumFreeHeapBytes=minimumFreeHeapBytes;this.queueTimeoutMillis=queueTimeoutMillis;
    }
    public Permit acquire(AIJobType requested,String requestId) {
        AIJobType type=InferencePriorityContext.effective(requested);
        if(Thread.currentThread().isInterrupted())throw new OllamaException("Inference cancelled before admission");
        long start=System.nanoTime();Instant queued=Instant.now();UUID id=UUID.randomUUID();
        trace(type,"STARTED",0);
        AIJobType blocker;int pending;
        synchronized(this) {
            if(waiting>=maxWaiting)throw new OllamaException("Model Gateway queue capacity reached");
            blocker=active.values().stream().findFirst().orElse(null);pending=waiting+active.size();
            waiting++;if(interactive(type))waitingInteractive++;
            try {
                while(!admissible(type)) {
                    long remaining=queueTimeoutMillis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
                    if(remaining<=0)throw new OllamaException("Model Gateway admission timeout");
                    try{wait(Math.min(remaining,100));}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new OllamaException("Inference cancelled while queued",ex);}
                }
                if(Thread.currentThread().isInterrupted())throw new OllamaException("Inference cancelled before admission");
                active.put(id,type);
            }finally{waiting--;if(interactive(type))waitingInteractive--;notifyAll();}
        }
        long waitMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        var diagnostics=com.jarvis.common.diagnostics.InferenceDiagnosticsContext.current();
        if(diagnostics!=null){diagnostics.setOllamaPermitQueueWaitMs(waitMillis);diagnostics.setRequestWaitedForAnotherOllamaJob(blocker!=null);diagnostics.setOllamaJobTypeBlockingRequest(blocker==null?null:blocker.name());}
        trace(type,"FINISHED",waitMillis);
        return new Permit(this,id,type,queued,Instant.now(),waitMillis,blocker,pending);
    }
    private void trace(AIJobType type,String phase,long duration) {
        if(!interactive(type))return;
        try { eventBus.publish(com.jarvis.common.event.CognitiveEventType.EXECUTION_TRACE,phase,"Model Gateway queue "+phase.toLowerCase(Locale.ROOT),null,
            Map.of("stage","OLLAMA_QUEUE_WAIT","phase",phase,"durationMs",duration,"queueWaitMs",duration,"jobType",type.name(),"severity",duration>500?"ORANGE":"GREEN"));
        } catch(RuntimeException ignored) { /* Diagnostics cannot leak an acquired inference permit. */ }
    }
    private boolean admissible(AIJobType type) {
        if(active.size()>=concurrency || contextTokenBudget>0 && (long)(active.size()+1)*contextTokens>contextTokenBudget)return false;
        if(!interactive(type)) {
            long background=active.values().stream().filter(t->!interactive(t)).count();
            if(background>=concurrency-reservedInteractive || chatPriority && waitingInteractive>0)return false;
        }
        Runtime runtime=Runtime.getRuntime();
        return runtime.maxMemory()-(runtime.totalMemory()-runtime.freeMemory())>=minimumFreeHeapBytes;
    }
    private boolean interactive(AIJobType type){return type==AIJobType.CHAT || type==AIJobType.MAIN_MODEL;}
    private synchronized void release(UUID id){active.remove(id);notifyAll();}
    public synchronized Map<String,Object> snapshot(){return Map.of("configuredConcurrency",concurrency,"activeInferenceRequests",active.size(),"waitingRequests",waiting,"reservedInteractive",reservedInteractive);}
    public static final class Permit implements com.jarvis.common.ai.ModelGateway.Lease {
        private final OllamaRequestCoordinator coordinator;private final UUID id;private final AIJobType jobType,activeJobAtQueueTime;
        private final Instant queuedAt,startedAt;private final long queueWaitMs;private final int pendingJobs;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Permit(OllamaRequestCoordinator c,UUID id,AIJobType type,Instant queued,Instant started,long wait,AIJobType blocker,int pending){coordinator=c;this.id=id;jobType=type;queuedAt=queued;startedAt=started;queueWaitMs=wait;activeJobAtQueueTime=blocker;pendingJobs=pending;}
        public AIJobType jobType(){return jobType;}public Instant queuedAt(){return queuedAt;}public Instant startedAt(){return startedAt;}public long queueWaitMs(){return queueWaitMs;}public AIJobType activeJobAtQueueTime(){return activeJobAtQueueTime;}public int pendingJobs(){return pendingJobs;}
        public void close(){if(closed.compareAndSet(false,true))coordinator.release(id);}
    }
}
