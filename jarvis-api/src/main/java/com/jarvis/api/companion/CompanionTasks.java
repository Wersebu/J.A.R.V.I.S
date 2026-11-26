package com.jarvis.api.companion;

import com.jarvis.api.service.ChatService;
import com.jarvis.common.companion.*;
import com.jarvis.tools.taskplan.*;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.ChatRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Durable task engine. All transitions and snapshots share one lock. */
@Service
public class CompanionTasks implements AutoCloseable {
    private static final String VERSION = "1.7.0";
    public static String version() { return VERSION; }
    public record Request(String message, String role, Integer priority, Integer timeoutSeconds, Integer maxAttempts,
                          String name,List<String> dependsOn,String conversationId,String parentRequestId) {
        public Request(String message,String role,Integer priority,Integer timeoutSeconds,Integer maxAttempts){this(message,role,priority,timeoutSeconds,maxAttempts,null,List.of(),null,null);}
        public Request{dependsOn=dependsOn==null?List.of():List.copyOf(dependsOn);}
    }
    public record Task(String taskId, String role, String state, int priority, Double progress,
                       Instant createdAt, Instant updatedAt, String result, String error, String conversationId, String executionConversationId) {
        public Task(String id,String role,String state,int priority,Double progress,Instant created,Instant updated,String result,String error) {
            this(id,role,state,priority,progress,created,updated,result,error,null,"companion-"+id);
        }
    }
    public record Event(String eventId, String protocolVersion, Instant timestamp, long sequence,
                        String type, String taskId, Object payload) {}
    public record Replay(boolean resetRequired, List<Event> events, long latestSequence) {}
    public record Submission(Request request,String name,String groupId,Instant deadline,String languageReference) {
        public Submission(Request request,String name,String groupId,Instant deadline){this(request,name,groupId,deadline,null);}
    }
    public record Delivery(String messageId,String conversationId,String taskId,String kind,String text,Instant createdAt,String requestId,String groupId) {}
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CompanionTasks.class);
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private TaskResultDelivery resultDelivery;
    private final ScheduledExecutorService continuations=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"companion-continuation");t.setDaemon(true);return t;});
    private boolean historyReady;
    private final java.util.concurrent.atomic.AtomicBoolean delivering=new java.util.concurrent.atomic.AtomicBoolean();
    private CompanionDocumentStore history=new InMemoryCompanionStore();
    private ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private DynamicPlanService plans;
    private int retentionDays=30;
    public boolean durableHistory(){return history.durable();}
    @org.springframework.beans.factory.annotation.Autowired
    public synchronized void configureHistory(CompanionDocumentStore store,ObjectMapper mapper,DynamicPlanService plans,@Value("${jarvis.companion.history.retention-days:30}") int retentionDays){
        if(!tasks.isEmpty())throw new IllegalStateException("History must initialize before accepting tasks");
        this.history=store;this.mapper=mapper.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);this.plans=plans;this.retentionDays=retentionDays;
        plans.attach(this,this::publishPlan);
        for(var document:history.all("task")){
            Task restored;try{restored=this.mapper.readValue(document.body(),Task.class);}catch(Exception e){throw new IllegalStateException("Corrupt task history; refusing to discard it",e);}
            Submission input=history.find(document.owner(),"task-input",restored.taskId()).map(d->decode(d.body(),Submission.class)).orElse(null);
            Entry entry=new Entry(document.owner(),restored,input);tasks.put(restored.taskId(),entry);sequences.putIfAbsent(document.owner(),0L);
            plans.register(entry.owner,restored.taskId(),restored.state());
            boolean resumable=Set.of("QUEUED","WAITING").contains(restored.state())&&input!=null&&input.deadline().isAfter(Instant.now());
            boolean interrupted=!resumable&&(!TERMINAL.contains(restored.state()) || restored.state().equals("COMPLETED")&&plans.find(entry.owner,restored.taskId()).filter(p->!p.status().equals("COMPLETED")).isPresent());
            if(interrupted){Task recovered=new Task(restored.taskId(),restored.role(),"FAILED",restored.priority(),null,restored.createdAt(),Instant.now(),restored.result(),"Interrupted by backend restart; external work was not retried",restored.conversationId(),restored.executionConversationId());persist(entry.owner,recovered);entry.task=recovered;}
            plans.taskState(entry.owner,restored.taskId(),entry.task.state(),!resumable);
            if(resumable){entry.alarm=timer.schedule(()->expire(entry),Math.max(1,java.time.Duration.between(Instant.now(),input.deadline()).toMillis()),TimeUnit.MILLISECONDS);if(restored.state().equals("QUEUED"))workers.execute(entry);}
        }
        purgeHistory();
        historyReady=true;
    }
    private <T>T decode(String text,Class<T> type){try{return mapper.readValue(text,type);}catch(Exception e){throw new IllegalStateException("Corrupt task state",e);}}
    private CompanionDocumentStore.Document document(String owner,String kind,String id,Object body){try{return new CompanionDocumentStore.Document(owner,kind,id,mapper.writeValueAsString(body),Instant.now());}catch(Exception e){throw new IllegalStateException("Could not serialize task state",e);}}
    private void persist(String owner,Task task){try{history.put(new CompanionDocumentStore.Document(owner,"task",task.taskId(),mapper.writeValueAsString(task),task.updatedAt()));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Could not serialize public task history",e);}}
    private void publishPlan(String owner,String type,String id,DynamicPlan plan){synchronized(this){Entry e=owned(owner,id);var payload=mapper.convertValue(e.task,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});payload.putAll(info(owner,id));payload.put("plan",plan);publish(owner,type,id,payload);}}
    private void publish(String owner,String type,String id,Object payload){long sequence=sequences.merge(owner,1L,Long::sum);var journal=events.computeIfAbsent(owner,k->new ArrayDeque<>());journal.add(new Event(UUID.randomUUID().toString(),VERSION,Instant.now(),sequence,type,id,payload));while(journal.size()>512)journal.removeFirst();}
    private boolean deliveryPending(Entry e){return e.task.conversationId()!=null&&history.find(e.owner,"task-delivery","task-result/"+e.task.taskId()).isEmpty();}
    public synchronized DynamicPlan plan(String owner,String id){owned(owner,id);if(plans==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return plans.find(owner,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
    public synchronized List<DynamicPlan> planRevisions(String owner,String id){owned(owner,id);return plans==null?List.of():plans.revisions(owner,id);}
    public synchronized void delete(String owner,String id){Entry e=owned(owner,id);if(!TERMINAL.contains(e.task.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Active tasks cannot be deleted");history.deleteTask(owner,id);for(var key:history.documents(owner,"task-submission",""))if(key.body().equals(id))history.delete(owner,"task-submission",key.id());if(plans!=null)plans.forget(owner,id);tasks.remove(id);if(tasks.values().stream().noneMatch(other->other.owner.equals(owner)&&groupId(other).equals(groupId(e))))history.delete(owner,"task-synthesis","task-group/"+groupId(e));events.getOrDefault(owner,new ArrayDeque<>()).removeIf(event->id.equals(event.taskId()));Task tombstone=new Task(e.task.taskId(),e.task.role(),e.task.state(),e.task.priority(),null,e.task.createdAt(),e.task.updatedAt(),null,null);publish(owner,"task.history.deleted",id,tombstone);}
    private void purgeHistory(){Instant cutoff=Instant.now().minus(java.time.Duration.ofDays(Math.max(0,retentionDays)));var terminal=tasks.values().stream().filter(e->TERMINAL.contains(e.task.state())&&!deliveryPending(e)).sorted(Comparator.comparing(e->e.task.updatedAt())).toList();for(var e:terminal){if(retentionDays>0&&e.task.updatedAt().isBefore(cutoff)||tasks.size()>1000)delete(e.owner,e.task.taskId());}}
    private final ChatService chat;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.jarvis.common.ai.ModelGateway gateway;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ActivityTracker activityTracker;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ResourceTelemetry resourceTelemetry;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private TaskAnnouncements announcements;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;
    private final Map<String, Entry> tasks = new LinkedHashMap<>();
    private final Map<String, ArrayDeque<Event>> events = new HashMap<>();
    private final Map<String, Long> sequences = new HashMap<>();
    private final String serverInstanceId=UUID.randomUUID().toString();
    public String serverInstanceId(){return serverInstanceId;}
    private long order;
    private boolean closed;
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "PARTIALLY_COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT");
    private final class Entry implements Runnable, Comparable<Entry> {
        final String owner, message; final long position; final long deadline;
        Submission submission;
        final ArrayDeque<String> instructions=new ArrayDeque<>(); boolean acceptingInstructions=true;
        Task task; Thread runner; boolean timeout; ScheduledFuture<?> alarm;
        Entry(String owner, Request r) {
            this.owner=owner; message=r.message(); position=++order;
            Instant now=Instant.now(); deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(r.timeoutSeconds()==null?120:r.timeoutSeconds());
            task=new Task(UUID.randomUUID().toString(), r.role()==null?"TASK":r.role(), "QUEUED",
                r.priority()==null?50:r.priority(), null, now, now, null, null);
        }
        Entry(String owner,Task restored,Submission input){this.owner=owner;this.submission=input;this.message=input==null?"":input.request().message();this.position=++order;this.deadline=input==null?Long.MAX_VALUE:System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(java.time.Duration.between(Instant.now(),input.deadline()).toMillis());this.task=restored;}
        public int compareTo(Entry other) { int n=Integer.compare(task.priority(),other.task.priority()); return n==0?Long.compare(position,other.position):n; }
        public void run() {
            synchronized(CompanionTasks.this) {
                if(TERMINAL.contains(task.state())) return;
                if(System.nanoTime()>=deadline) { timeout=true; change(this,"TIMED_OUT",null,null); return; }
                runner=Thread.currentThread(); change(this,"RUNNING",null,null);
            }
            String[] result={null}; String failure=null;
            try { CurrentUserContext.runAs(owner, () -> TaskExecutionContext.run(task.taskId(),(state,detail)->executionStatus(this,state,detail),submission==null?null:submission.languageReference(), () -> TaskInstructionContext.run(()->drainInstructions(owner,task.taskId()), () -> com.jarvis.common.ai.InferencePriorityContext.runBackground(() -> result[0]=com.jarvis.common.ai.InferenceProfileContext.call(task.role().equals("RESEARCH")?"research":"planning",()->executeConversation(this)))))); }
            catch(Exception ex) { failure=ex instanceof com.jarvis.common.companion.IncompleteTaskException ? ex.getMessage() : ex.getClass().getSimpleName();if(ex instanceof com.jarvis.common.companion.IncompleteTaskException incomplete)result[0]=incomplete.partialResult();LOG.warn("Background task {} failed",task.taskId(),ex); }
            finally {
                synchronized(CompanionTasks.this) {
                    runner=null;
                    if(timeout) change(this,"TIMED_OUT",null,null);
                    else if(task.state().equals("CANCEL_REQUESTED")) change(this,"CANCELLED",null,null);
                    else if(failure!=null) change(this,result[0]!=null&&!result[0].isBlank()?"PARTIALLY_COMPLETED":"FAILED",result[0],failure);
                    else if(result[0]==null||result[0].isBlank()) change(this,"FAILED",null,"EMPTY_RESULT: model did not produce a deliverable");
                    else if(plans!=null&&!plans.verifiedComplete(owner,task.taskId()))change(this,"FAILED",null,"Executable plan is incomplete or unverified");
                    else change(this,"COMPLETED",result[0],null);
                    if(alarm!=null) alarm.cancel(false);
                }
                Thread.interrupted();
            }
        }
    }
    public CompanionTasks(ChatService chat, @Value("${jarvis.companion.task-concurrency:2}") int concurrency) {
        if(concurrency<1 || concurrency>16) throw new IllegalArgumentException("task-concurrency must be 1..16");
        this.chat=chat;
        workers=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.SECONDS,new PriorityBlockingQueue<>(),r->{Thread t=new Thread(r,"companion-task");t.setDaemon(true);return t;});
        workers.prestartAllCoreThreads();
        var deadlines=new ScheduledThreadPoolExecutor(1,r->{Thread t=new Thread(r,"companion-timeout");t.setDaemon(true);return t;});
        deadlines.setRemoveOnCancelPolicy(true);
        timer=deadlines;
        continuations.scheduleWithFixedDelay(this::processCompletions,250,1000,TimeUnit.MILLISECONDS);
    }
    public synchronized Task submit(String owner, Request request) { return submit(owner,request,request==null?null:request.conversationId(),request==null?null:request.parentRequestId()); }
    public synchronized Task submit(String owner, Request request,String sourceConversation,String key) {
        if(closed) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if(request==null || request.message()==null || request.message().isBlank() || request.message().length()>32000
            || (request.role()!=null && !Set.of("TASK","RESEARCH").contains(request.role()))
            || (request.priority()!=null && (request.priority()<0 || request.priority()>100))
            || (request.timeoutSeconds()!=null && (request.timeoutSeconds()<1 || request.timeoutSeconds()>3600))
            || (request.name()!=null&&(request.name().isBlank()||request.name().length()>120))||request.dependsOn().size()>16
            || (request.maxAttempts()!=null && request.maxAttempts()!=1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid task request; retries are not safe for tool execution");
        for(String dependency:request.dependsOn())if(!Objects.equals(owned(owner,dependency).task.conversationId(),sourceConversation))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Dependencies must belong to the same conversation");
        String dedup=key==null?null:submissionKey(key+"\n"+sourceConversation,request.message(),request.role());
        if(dedup!=null){var previous=history.find(owner,"task-submission",dedup);if(previous.isPresent()&&tasks.containsKey(previous.get().body()))return get(owner,previous.get().body());}
        if(!sequences.containsKey(owner) && sequences.size()>=1024) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Owner capacity reached");
        long active=tasks.values().stream().filter(e->!TERMINAL.contains(e.task.state())).count();
        if(active>=128) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Task capacity reached");
        purgeHistory();
        if(tasks.size()>=1000){var oldest=tasks.values().stream().filter(e->TERMINAL.contains(e.task.state())&&!deliveryPending(e)).min(Comparator.comparing(e->e.task.updatedAt()));oldest.ifPresent(e->delete(e.owner,e.task.taskId()));}
        if(tasks.size()>=1000)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Undelivered results must be processed before accepting more tasks");
        Entry e=new Entry(owner,request);
        Task original=e.task;e.task=new Task(original.taskId(),original.role(),request.dependsOn().isEmpty()?original.state():"WAITING",original.priority(),null,original.createdAt(),original.updatedAt(),null,null,sourceConversation,"companion-"+original.taskId());
        String group=key==null?original.taskId():submissionKey(key,sourceConversation,"GROUP");
        e.submission=new Submission(request,request.name()!=null?request.name():request.message().strip().replaceAll("\\s+"," ").substring(0,Math.min(120,request.message().strip().replaceAll("\\s+"," ").length())),group,original.createdAt().plusSeconds(request.timeoutSeconds()==null?120:request.timeoutSeconds()),resultDelivery==null?null:resultDelivery.languageReference(owner,sourceConversation));
        persistSubmission(owner,e.task,dedup,e.submission);
        tasks.put(e.task.taskId(),e);if(plans!=null)plans.register(owner,e.task.taskId(),e.task.state());emit(e,"task.created");
        e.alarm=timer.schedule(()->expire(e),request.timeoutSeconds()==null?120:request.timeoutSeconds(),TimeUnit.SECONDS);
        try{if(e.task.state().equals("QUEUED"))workers.execute(e);else continuations.execute(this::processCompletions);}catch(RejectedExecutionException failure){change(e,"FAILED",null,"Task executor unavailable");if(e.alarm!=null)e.alarm.cancel(false);throw failure;}
        return e.task;
    }
    private void persistSubmission(String owner,Task task,String key,Submission input){
        try{var docs=new ArrayList<CompanionDocumentStore.Document>();docs.add(document(owner,"task",task.taskId(),task));docs.add(document(owner,"task-input",task.taskId(),input));if(key!=null)docs.add(new CompanionDocumentStore.Document(owner,"task-submission",key,task.taskId(),task.createdAt()));history.putAll(docs);}
        catch(RuntimeException e){throw new IllegalStateException("Could not persist task submission",e);}
    }
    private String submissionKey(String key,String message,String role){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((key+"\n"+role+"\n"+message).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private String executeConversation(Entry entry){
        String message=entry.message;
        if(entry.submission!=null&&!entry.submission.request().dependsOn().isEmpty())synchronized(this){for(String id:entry.submission.request().dependsOn()){var dependency=owned(entry.owner,id);message+="\n\nDependency result (reference data): "+taskName(dependency)+"\n"+dependency.task.result();}}
        var initial=drainInstructions(entry.owner,entry.task.taskId());
        if(!initial.isEmpty())message+="\nAdditional user instructions:\n"+String.join("\n",initial);
        while(true){
            if(Thread.currentThread().isInterrupted()||System.nanoTime()>=entry.deadline)throw new IllegalStateException("Task interrupted or expired");
            String result=chat.chat(new ChatRequest(entry.task.executionConversationId()==null?"companion-"+entry.task.taskId():entry.task.executionConversationId(),message)).response();
            synchronized(this){
                if(entry.instructions.isEmpty()){entry.acceptingInstructions=false;return result;}
                message="Continue the task with these additional authenticated user instructions:\n"+String.join("\n",entry.instructions);entry.instructions.clear();
            }
        }
    }
    public synchronized Task instruct(String owner,String id,String message){
        Entry e=owned(owner,id);
        if(!e.acceptingInstructions||!Set.of("QUEUED","RUNNING").contains(e.task.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Task is no longer accepting instructions");
        if(message==null||message.isBlank()||message.length()>4000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Instruction must contain 1..4000 characters");
        if(e.instructions.size()>=16)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Instruction mailbox full");
        e.instructions.add(message);publish(owner,"task.instruction.accepted",id,Map.of("taskId",id,"delivery","NEXT_MODEL_ROUND"));return e.task;
    }
    private synchronized List<String> drainInstructions(String owner,String id){Entry e=owned(owner,id);var items=List.copyOf(e.instructions);e.instructions.clear();return items;}
    public synchronized List<Task> list(String owner) { return tasks.values().stream().filter(e->e.owner.equals(owner)).map(e->e.task).sorted(Comparator.comparing(Task::createdAt).reversed()).toList(); }
    private Entry owned(String owner,String id) { Entry e=tasks.get(id); if(e==null || !e.owner.equals(owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND); return e; }
    public synchronized Task get(String owner,String id) { return owned(owner,id).task; }
    public synchronized Map<String,Object> info(String owner,String id){var e=owned(owner,id);return Map.of("taskId",id,"name",taskName(e),"groupId",groupId(e),"dependsOn",e.submission==null?List.of():e.submission.request().dependsOn(),"resultRef","/api/v1/companion/tasks/"+id+"/result");}
    public synchronized Task cancel(String owner,String id) {
        Entry e=owned(owner,id);
        if(!TERMINAL.contains(e.task.state()) && !e.task.state().equals("CANCEL_REQUESTED")) {
            change(e,"CANCEL_REQUESTED",null,null);
            if(e.runner==null) { workers.remove(e); change(e,"CANCELLED",null,null); if(e.alarm!=null)e.alarm.cancel(false); }
            else e.runner.interrupt();
        }
        return e.task;
    }
    private synchronized void expire(Entry e) {
        if(TERMINAL.contains(e.task.state())) return;
        e.timeout=true;
        if(e.runner==null) { workers.remove(e); change(e,"TIMED_OUT",null,null); }
        else { if(!e.task.state().equals("CANCEL_REQUESTED")) change(e,"CANCEL_REQUESTED",null,null); e.runner.interrupt(); }
    }
    private synchronized void executionStatus(Entry e,String state,String detail){if(Set.of("RUNNING","RETRYING").contains(e.task.state())&&Set.of("RUNNING","RETRYING").contains(state)&&!e.task.state().equals(state))change(e,state,null,detail);}
    private void change(Entry e,String state,String result,String error) {
        if(TERMINAL.contains(e.task.state()))return;
        Task t=e.task; Task next=new Task(t.taskId(),t.role(),state,t.priority(),null,t.createdAt(),Instant.now(),result,error,t.conversationId(),t.executionConversationId());
        persist(e.owner,next);e.task=next;
        if(plans!=null)plans.taskState(e.owner,next.taskId(),state,false);
        emit(e,"task."+switch(state){case "RUNNING"->"started";case "CANCEL_REQUESTED"->"cancel_requested";default->state.toLowerCase(Locale.ROOT);});
        if(TERMINAL.contains(state)&&!closed)continuations.execute(this::processCompletions);
    }
    private void emit(Entry e,String type) {
        long seq=sequences.merge(e.owner,1L,Long::sum);
        var log=events.computeIfAbsent(e.owner,k->new ArrayDeque<>());
        var payload=mapper.convertValue(e.task,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});payload.putAll(info(e.owner,e.task.taskId()));
        log.add(new Event(UUID.randomUUID().toString(),VERSION,Instant.now(),seq,type,e.task.taskId(),payload));
        String kind=switch(type){case "task.created"->"ACKNOWLEDGMENT";case "task.started"->"PROGRESS";case "task.completed"->"COMPLETED";case "task.failed","task.partially_completed"->"FAILED";default->null;};
        if(announcements!=null && kind!=null) {
            Task snapshot=e.task;
            announcements.generate(e.owner,snapshot,kind,text->{synchronized(CompanionTasks.this){
                if(!e.task.state().equals(snapshot.state()))return;
                long announcementSeq=sequences.merge(e.owner,1L,Long::sum);
                var journal=events.get(e.owner);
                journal.add(new Event(UUID.randomUUID().toString(),VERSION,Instant.now(),announcementSeq,"assistant.task.announcement",snapshot.taskId(),Map.of("taskId",snapshot.taskId(),"kind",kind,"state",snapshot.state(),"text",text)));
                while(journal.size()>512)journal.removeFirst();
            }});
        }
        while(log.size()>512)log.removeFirst();
    }
    public synchronized Replay replay(String owner,long after) {
        long last=sequences.getOrDefault(owner,0L); var log=events.getOrDefault(owner,new ArrayDeque<>());
        boolean reset=after<0 || after>last || (!log.isEmpty() && after<log.getFirst().sequence()-1);
        var tail=log.stream().filter(e->e.sequence()>after).toList();long expected=after+1;for(var event:tail){if(event.sequence()!=expected++)reset=true;}
        return new Replay(reset,reset?List.of():tail,last);
    }
    public synchronized Map<String,Object> state(String owner) {
        purgeHistory();
        List<Task> list=list(owner);
        List<DynamicPlan> currentPlans=plans==null?List.of():list.stream().map(t->plans.find(owner,t.taskId())).flatMap(Optional::stream).toList();
        return Map.of("plans",currentPlans,"contractVersion",VERSION,"serverInstanceId",serverInstanceId,"latestSequence",sequences.getOrDefault(owner,0L),"tasks",list,"activeProcesses",list.stream().filter(t->!TERMINAL.contains(t.state())).count(),"metrics",metrics(owner),"deliveries",history.documents(owner,"task-delivery","").stream().map(d->decode(d.body(),Delivery.class)).toList(),"taskInfo",list.stream().map(t->{var e=tasks.get(t.taskId());return Map.of("taskId",t.taskId(),"name",taskName(e),"groupId",groupId(e));}).toList());
    }
    private String taskName(Entry e){return e.submission==null?e.task.role():e.submission.name();}
    private String groupId(Entry e){return e.submission==null?e.task.taskId():e.submission.groupId();}

    /** Terminal task rows are the durable outbox. Receipts follow idempotent conversation writes. */
    void processCompletions(){
        if(!delivering.compareAndSet(false,true))return;
        try{
            List<Entry> ready;
            synchronized(this){
                if(closed)return;
                for(var waiting:List.copyOf(tasks.values()))if(waiting.task.state().equals("WAITING")&&waiting.submission!=null){
                    var dependencies=waiting.submission.request().dependsOn().stream().map(id->tasks.get(id)).toList();
                    if(dependencies.stream().anyMatch(d->d==null||TERMINAL.contains(d.task.state())&&!d.task.state().equals("COMPLETED")))change(waiting,"FAILED",null,"DEPENDENCY_FAILED: required task has no complete result");
                    else if(dependencies.stream().allMatch(d->d.task.state().equals("COMPLETED"))){change(waiting,"QUEUED",null,null);workers.execute(waiting);}
                }
                if(!historyReady||resultDelivery==null)return;
                ready=tasks.values().stream().filter(e->TERMINAL.contains(e.task.state())&&e.task.conversationId()!=null).toList();
            }
            for(var e:ready){
                try{
                String id="task-result/"+e.task.taskId();
                if(history.find(e.owner,"task-delivery",id).isEmpty()){
                    String heading=e.task.state().equals("COMPLETED")?"Wynik zadania: ":"Zadanie zakończone bez pełnego wyniku: ";
                    String text=heading+taskName(e)+"\n\n"+(e.task.result()==null?"":e.task.result())+(e.task.error()==null?"":"\n\nInformacja o błędzie: "+e.task.error());
                    deliver(e,id,"RESULT",text);
                }
                List<Entry> group;
                synchronized(this){group=tasks.values().stream().filter(t->t.owner.equals(e.owner)&&Objects.equals(t.task.conversationId(),e.task.conversationId())&&groupId(t).equals(groupId(e))).toList();}
                if(group.size()>1&&group.stream().allMatch(t->TERMINAL.contains(t.task.state())&&history.find(t.owner,"task-delivery","task-result/"+t.task.taskId()).isPresent())){
                    String finalId="task-group/"+groupId(e);
                    if(history.find(e.owner,"task-delivery",finalId).isEmpty()){
                        String synthesis=history.find(e.owner,"task-synthesis",finalId).map(CompanionDocumentStore.Document::body).orElse(null);
                        if(synthesis==null){
                            StringBuilder evidence=new StringBuilder();int allowance=Math.max(80,12000/group.size());
                            for(var child:group){String result=Objects.toString(child.task.result(),"Brak wyniku");evidence.append(taskName(child)).append(" [").append(child.task.state()).append("] ").append(Objects.toString(child.task.error(),"")).append('\n').append(result,0,Math.min(result.length(),allowance)).append(result.length()>allowance?" [fragment; pełny materiał w załączonej treści]":"").append('\n');}
                            try{synthesis=resultDelivery.synthesize(e.owner,e.task.conversationId(),evidence.toString());}
                            catch(Exception failure){LOG.warn("Main-model synthesis unavailable; delivering all exact results",failure);synthesis="Synteza modelowa jest niedostępna. Poniżej zachowano wszystkie wyniki i informacje o błędach.";}
                            history.put(new CompanionDocumentStore.Document(e.owner,"task-synthesis",finalId,synthesis,Instant.now()));
                        }
                        StringBuilder text=new StringBuilder("Zakończono wszystkie powiązane zadania. ").append(synthesis).append("\n\nKomplet wyników:\n");
                        for(var child:group)text.append("\n## ").append(taskName(child)).append("\n").append(child.task.result()==null?"Brak pełnego wyniku.":child.task.result()).append(child.task.error()==null?"":"\nBłąd: "+child.task.error()).append('\n');
                        deliver(e,finalId,"AGGREGATE",text.toString());
                    }
                }
                }catch(Exception failure){LOG.error("Delivery deferred for task {}; other results remain eligible",e.task.taskId(),failure);}
            }
        }catch(Exception failure){LOG.error("Autonomous task delivery deferred; durable task results retained",failure);}
        finally{delivering.set(false);}
    }
    private void deliver(Entry e,String id,String kind,String text){
        var delivery=new Delivery(TaskResultDelivery.messageId(e.owner,e.task.conversationId(),id).toString(),e.task.conversationId(),e.task.taskId(),kind,text,Instant.now(),id,groupId(e));
        var status=resultDelivery.deliver(e.owner,delivery.conversationId(),id,text,delivery.createdAt());
        if(status==TaskResultDelivery.Status.BUSY)return;
        synchronized(this){
            if(!tasks.containsKey(e.task.taskId()))return;
            history.put(document(e.owner,"task-delivery",id,delivery));
            if(status==TaskResultDelivery.Status.DELIVERED){var payload=mapper.convertValue(delivery,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});payload.put("voicePolicy","INHERIT_DEFER_UNTIL_IDLE");payload.put("interruptActiveConversation",false);publish(e.owner,"assistant.message.created",e.task.taskId(),payload);}
        }
    }
    public synchronized Map<String,Object> metrics(String owner){
        var own=list(owner);var result=new LinkedHashMap<String,Object>();
        long running=own.stream().filter(t->t.state().equals("RUNNING")||t.state().equals("RETRYING")||t.state().equals("CANCEL_REQUESTED")).count();
        result.put("observedAt",Instant.now());result.put("scope","OWNER");result.put("activeTasks",running);result.put("activeCompanionTasks",running);
        result.put("queuedTasks",own.stream().filter(t->t.state().equals("QUEUED")).count());
        if(activityTracker!=null)result.putAll(activityTracker.snapshot(owner));
        long coding=((Number)result.getOrDefault("activeCodingTasks",0L)).longValue();
        result.put("activeTasks",running+coding);
        result.put("activeAgents",running+coding+((Number)result.getOrDefault("activeSynchronousAgents",0L)).longValue());
        if(gateway!=null)result.putAll(gateway.snapshot(owner));
        if(resourceTelemetry!=null)result.put("resources",resourceTelemetry.snapshot());
        return result;
    }
    @PreDestroy public void close() {
        synchronized(this){closed=true;workers.shutdownNow();timer.shutdownNow();continuations.shutdownNow();}
        try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        try{continuations.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
