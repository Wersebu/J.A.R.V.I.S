package com.jarvis.ollama;

import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.event.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One process-local admission gate. HTTP and streaming never execute under its monitor. */
@Service
public class OllamaRequestCoordinator implements ModelGateway {
    private final int concurrency,reservedInteractive,maxWaiting;
    private final long minimumFreeHeapBytes,queueTimeoutMillis;
    private final boolean chatPriority;
    private final CognitiveEventBus eventBus;
    private final Map<UUID,Waiting> active=new LinkedHashMap<>();
    private final Map<UUID,Long> admitted=new HashMap<>();
    private final List<Waiting> queue=new ArrayList<>();
    private long order;
    private int contextTokens=24576;
    private long backgroundSpacingNanos,lastBackgroundAdmission;
    @Autowired
    public synchronized void backgroundSpacing(@Value("${jarvis.model-gateway.background-submit-spacing-millis:250}") long millis){
        if(millis<0||millis>10000)throw new IllegalArgumentException("Background submit spacing must be 0..10000 ms");
        backgroundSpacingNanos=TimeUnit.MILLISECONDS.toNanos(millis);notifyAll();
    }
    private long contextTokenBudget;
    private record Waiting(UUID id,AIJobType type,String requestId,String owner,String taskId,String profile,int priority,long order,long nano,Thread thread,InferenceSettingsContext.Settings settings) {}
    @Autowired(required=false)
    public synchronized void contextBudget(@Value("${jarvis.inference.physical-context-window:24576}") int context,
        @Value("${jarvis.model-gateway.context-token-budget:0}") long budget) {
        if(context<1||budget<0||budget>0&&budget<context)throw new IllegalArgumentException("Invalid context token budget");
        contextTokens=context;contextTokenBudget=budget;
    }
    public OllamaRequestCoordinator(boolean priority,CognitiveEventBus bus){this(priority,bus,1,0,128,0,300000);}
    @Autowired
    public OllamaRequestCoordinator(@Value("${jarvis.memory.background.chat-priority:true}") boolean priority,CognitiveEventBus bus,
        @Value("${jarvis.model-gateway.concurrency:1}") int concurrency,@Value("${jarvis.model-gateway.reserved-interactive:0}") int reserved,
        @Value("${jarvis.model-gateway.max-waiting:128}") int maxWaiting,@Value("${jarvis.model-gateway.minimum-free-heap-bytes:0}") long heap,
        @Value("${jarvis.model-gateway.queue-timeout-millis:300000}") long timeout) {
        if(concurrency<1||concurrency>64||reserved<0||reserved>concurrency||reserved>=concurrency&&concurrency>1||maxWaiting<1||heap<0||timeout<1)
            throw new IllegalArgumentException("Invalid Model Gateway admission configuration");
        this.chatPriority=priority;eventBus=bus;this.concurrency=concurrency;reservedInteractive=concurrency==1?0:reserved;
        this.maxWaiting=maxWaiting;minimumFreeHeapBytes=heap;queueTimeoutMillis=timeout;
    }
    public Permit acquire(AIJobType requested,String requestId) {
        var settings=InferenceSettingsContext.current();
        AIJobType type=settings!=null&&settings.background()?AIJobType.BACKGROUND:InferencePriorityContext.effective(requested);
        long start=System.nanoTime();Instant queuedAt=Instant.now();
        long deadline=start+TimeUnit.MILLISECONDS.toNanos(queueTimeoutMillis);
        if(settings!=null)deadline=Math.min(deadline,settings.deadlineNanos());
        Waiting waiter;AIJobType blocker;int pending;
        synchronized(this){
            checkInterrupted();
            waiter=new Waiting(UUID.randomUUID(),type,requestId,CurrentUserContext.requiredUserId(),
                com.jarvis.common.companion.TaskExecutionContext.current(),settings==null?type.name():settings.profile(),
                settings==null?(interactive(type)&&chatPriority?10:50):settings.priority(),++order,start,Thread.currentThread(),settings);
            if(queue.size()>=maxWaiting&&!eligible(waiter))throw new OllamaException("Model Gateway queue capacity reached");
            // A blocked reentrant admission cannot release the parent's lease: fail instead of deadlocking.
            if(!eligible(waiter)&&active.values().stream().anyMatch(w->w.thread()==Thread.currentThread()))
                throw new OllamaException("Nested model admission would deadlock; release parent inference before tool execution");
            blocker=active.values().stream().findFirst().map(Waiting::type).orElse(null);pending=queue.size()+active.size();
            queue.add(waiter);
            try {
                boolean queueReported = false;
                while(!next(waiter)){
                    if (!queueReported) {
                        queueReported = true;
                        admissionEvent(CognitiveEventType.INFERENCE_QUEUED, "QUEUED", "Waiting for Model Gateway admission", null,
                                Map.of("reason", active.size() >= concurrency ? "INFERENCE_CAPACITY" : "GATEWAY_ADMISSION",
                                        "inferenceId", waiter.id().toString(), "profileId", waiter.profile(), "waitingRequests", queue.size()));
                    }
                    checkInterrupted();long remaining=deadline-System.nanoTime();
                    if(remaining<=0)throw new OllamaException("Model Gateway admission timeout");
                    try{TimeUnit.NANOSECONDS.timedWait(this,Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)));}
                    catch(InterruptedException e){Thread.currentThread().interrupt();throw new OllamaException("Inference cancelled while queued",e);}
                }
                checkInterrupted();if(System.nanoTime()>=deadline)throw new OllamaException("Model Gateway admission timeout");
                active.put(waiter.id(),waiter);
                admitted.put(waiter.id(),System.nanoTime());
                if(!interactive(type))lastBackgroundAdmission=System.nanoTime();
            }finally{queue.remove(waiter);notifyAll();}
        }
        long waitMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
        var diagnostics=com.jarvis.common.diagnostics.InferenceDiagnosticsContext.current();
        if(diagnostics!=null){diagnostics.setOllamaPermitQueueWaitMs(waitMs);diagnostics.setRequestWaitedForAnotherOllamaJob(blocker!=null);diagnostics.setOllamaJobTypeBlockingRequest(blocker==null?null:blocker.name());}
        trace(waiter,"FINISHED",waitMs);
        admissionEvent(CognitiveEventType.INFERENCE_STARTED, "ADMITTED", "Model Gateway admitted inference", null,
                Map.of("inferenceId", waiter.id().toString(), "queueWaitMs", waitMs, "profileId", waiter.profile()));
        return new Permit(this,waiter,queuedAt,Instant.now(),waitMs,blocker,pending);
    }
    private void admissionEvent(CognitiveEventType type, String status, String message, String node, Map<String,Object> metadata) {
        try { eventBus.publish(type, status, message, node, metadata); } catch (RuntimeException ignored) { }
    }
    private void checkInterrupted(){if(Thread.currentThread().isInterrupted())throw new OllamaException("Inference cancelled before admission");}
    private boolean next(Waiting w){
        if(!eligible(w))return false;
        return queue.stream().filter(this::eligible).min(Comparator.comparingLong(this::score).thenComparingLong(Waiting::order)).orElse(null)==w;
    }
    private long score(Waiting w){return w.priority()-TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-w.nano())/2;}
    private boolean eligible(Waiting w){
        if(active.size()>=concurrency||contextTokenBudget>0&&(long)(active.size()+1)*contextTokens>contextTokenBudget)return false;
        if(!interactive(w.type())&&active.values().stream().filter(a->!interactive(a.type())).count()>=concurrency-reservedInteractive)return false;
        // Limit bursts of background prompt submissions, without holding a slot while waiting or waiting for prior generation to finish.
        if(!interactive(w.type())&&lastBackgroundAdmission!=0&&System.nanoTime()-lastBackgroundAdmission<backgroundSpacingNanos)return false;
        Runtime r=Runtime.getRuntime();return r.maxMemory()-(r.totalMemory()-r.freeMemory())>=minimumFreeHeapBytes;
    }
    private boolean interactive(AIJobType type){return type==AIJobType.CHAT||type==AIJobType.MAIN_MODEL;}
    private void trace(Waiting w,String phase,long elapsed){try{eventBus.publish(CognitiveEventType.EXECUTION_TRACE,phase,"Model Gateway admission",null,
        Map.of("stage","OLLAMA_QUEUE_WAIT","phase",phase,"queueWaitMs",elapsed,"durationMs",elapsed,"jobType",w.type().name(),"inferenceId",w.id().toString(),"profileId",w.profile()));}catch(RuntimeException ignored){}}
    private synchronized void release(UUID id){active.remove(id);admitted.remove(id);notifyAll();}
    public synchronized Map<String,Object> snapshot(){return Map.of("configuredConcurrency",concurrency,"activeInferenceRequests",active.size(),"waitingRequests",queue.size(),"reservedInteractive",reservedInteractive,"backgroundSubmitSpacingMs",TimeUnit.NANOSECONDS.toMillis(backgroundSpacingNanos));}
    @Override public synchronized Map<String,Object> snapshot(String owner){
        var requests=new ArrayList<Map<String,Object>>();
        for(var w:active.values())if(w.owner().equals(owner))requests.add(detail(w,"ADMITTED"));
        for(var w:queue)if(w.owner().equals(owner))requests.add(detail(w,"WAITING_GATEWAY"));
        return Map.of("inferenceActive",active.values().stream().filter(w->w.owner().equals(owner)).count(),
            "inferenceQueued",queue.stream().filter(w->w.owner().equals(owner)).count(),"gatewaySlotsInUse",active.size(),
            "gatewaySlotsConfigured",concurrency,"reservedInteractive",reservedInteractive,"requests",requests,"capacityScope","PROCESS","backgroundSubmitSpacingMs",TimeUnit.NANOSECONDS.toMillis(backgroundSpacingNanos));
    }
    private Map<String,Object> detail(Waiting w,String phase){var m=new LinkedHashMap<String,Object>();m.put("inferenceId",w.id().toString());m.put("requestId",w.requestId());m.put("taskId",w.taskId());m.put("profileId",w.profile());m.put("phase",phase);long start=admitted.getOrDefault(w.id(),System.nanoTime());m.put("queueWaitMs",TimeUnit.NANOSECONDS.toMillis(start-w.nano()));m.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));if(w.settings()!=null){m.put("model",w.settings().model());m.put("physicalContext",w.settings().physicalContext());m.put("inputBudget",w.settings().inputBudget());}return m;}
    public static final class Permit implements ModelGateway.Lease {
        private final OllamaRequestCoordinator gate;private final Waiting waiter;private final Instant queuedAt,startedAt;
        private final long queueWaitMs;private final AIJobType blocker;private final int pending;
        private final AtomicBoolean closed=new AtomicBoolean();
        Permit(OllamaRequestCoordinator gate,Waiting w,Instant queued,Instant started,long elapsed,AIJobType blocker,int pending){this.gate=gate;waiter=w;queuedAt=queued;startedAt=started;queueWaitMs=elapsed;this.blocker=blocker;this.pending=pending;}
        public AIJobType jobType(){return waiter.type();}public Instant queuedAt(){return queuedAt;}public Instant startedAt(){return startedAt;}
        public long queueWaitMs(){return queueWaitMs;}public AIJobType activeJobAtQueueTime(){return blocker;}public int pendingJobs(){return pending;}
        public void close(){if(closed.compareAndSet(false,true))gate.release(waiter.id());}
    }
}
