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

/** Bounded process-local task engine. All transitions and snapshots share one lock. */
@Service
public class CompanionTasks implements AutoCloseable {
    private static final String VERSION = "1.4.0";
    public static String version() { return VERSION; }
    public record Request(String message, String role, Integer priority, Integer timeoutSeconds, Integer maxAttempts) {}
    public record Task(String taskId, String role, String state, int priority, Double progress,
                       Instant createdAt, Instant updatedAt, String result, String error) {}
    public record Event(String eventId, String protocolVersion, Instant timestamp, long sequence,
                        String type, String taskId, Object payload) {}
    public record Replay(boolean resetRequired, List<Event> events, long latestSequence) {}
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
            Entry entry=new Entry(document.owner(),restored);tasks.put(restored.taskId(),entry);sequences.putIfAbsent(document.owner(),0L);
            plans.register(entry.owner,restored.taskId(),restored.state());
            boolean interrupted=!TERMINAL.contains(restored.state()) || restored.state().equals("COMPLETED")&&plans.find(entry.owner,restored.taskId()).filter(p->!p.status().equals("COMPLETED")).isPresent();
            if(interrupted){Task recovered=new Task(restored.taskId(),restored.role(),"FAILED",restored.priority(),null,restored.createdAt(),Instant.now(),null,"Interrupted by backend restart; external work was not retried");persist(entry.owner,recovered);entry.task=recovered;}
            plans.taskState(entry.owner,restored.taskId(),entry.task.state(),true);
        }
        purgeHistory();
    }
    private void persist(String owner,Task task){try{history.put(new CompanionDocumentStore.Document(owner,"task",task.taskId(),mapper.writeValueAsString(task),task.updatedAt()));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Could not serialize public task history",e);}}
    private void publishPlan(String owner,String type,String id,DynamicPlan plan){synchronized(this){Entry e=owned(owner,id);var payload=mapper.convertValue(e.task,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});payload.put("plan",plan);publish(owner,type,id,payload);}}
    private void publish(String owner,String type,String id,Object payload){long sequence=sequences.merge(owner,1L,Long::sum);var journal=events.computeIfAbsent(owner,k->new ArrayDeque<>());journal.add(new Event(UUID.randomUUID().toString(),VERSION,Instant.now(),sequence,type,id,payload));while(journal.size()>512)journal.removeFirst();}
    public synchronized DynamicPlan plan(String owner,String id){owned(owner,id);if(plans==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND);return plans.find(owner,id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
    public synchronized List<DynamicPlan> planRevisions(String owner,String id){owned(owner,id);return plans==null?List.of():plans.revisions(owner,id);}
    public synchronized void delete(String owner,String id){Entry e=owned(owner,id);if(!TERMINAL.contains(e.task.state()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Active tasks cannot be deleted");history.deleteTask(owner,id);if(plans!=null)plans.forget(owner,id);tasks.remove(id);events.getOrDefault(owner,new ArrayDeque<>()).removeIf(event->id.equals(event.taskId()));Task tombstone=new Task(e.task.taskId(),e.task.role(),e.task.state(),e.task.priority(),null,e.task.createdAt(),e.task.updatedAt(),null,null);publish(owner,"task.history.deleted",id,tombstone);}
    private void purgeHistory(){Instant cutoff=Instant.now().minus(java.time.Duration.ofDays(Math.max(0,retentionDays)));var terminal=tasks.values().stream().filter(e->TERMINAL.contains(e.task.state())).sorted(Comparator.comparing(e->e.task.updatedAt())).toList();for(var e:terminal){if(retentionDays>0&&e.task.updatedAt().isBefore(cutoff)||tasks.size()>1000)delete(e.owner,e.task.taskId());}}
    private final ChatService chat;
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
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMED_OUT");
    private final class Entry implements Runnable, Comparable<Entry> {
        final String owner, message; final long position; final long deadline;
        Task task; Thread runner; boolean timeout; ScheduledFuture<?> alarm;
        Entry(String owner, Request r) {
            this.owner=owner; message=r.message(); position=++order;
            Instant now=Instant.now(); deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(r.timeoutSeconds()==null?120:r.timeoutSeconds());
            task=new Task(UUID.randomUUID().toString(), r.role()==null?"TASK":r.role(), "QUEUED",
                r.priority()==null?50:r.priority(), null, now, now, null, null);
        }
        Entry(String owner,Task restored){this.owner=owner;this.message="";this.position=++order;this.deadline=Long.MAX_VALUE;this.task=restored;}
        public int compareTo(Entry other) { int n=Integer.compare(task.priority(),other.task.priority()); return n==0?Long.compare(position,other.position):n; }
        public void run() {
            synchronized(CompanionTasks.this) {
                if(TERMINAL.contains(task.state())) return;
                if(System.nanoTime()>=deadline) { timeout=true; change(this,"TIMED_OUT",null,null); return; }
                runner=Thread.currentThread(); change(this,"RUNNING",null,null);
            }
            String[] result={null}; boolean failed=false;
            try { CurrentUserContext.runAs(owner, () -> TaskExecutionContext.run(task.taskId(), () -> com.jarvis.common.ai.InferencePriorityContext.runBackground(() -> result[0]=chat.chat(new ChatRequest("companion-"+task.taskId(),message)).response()))); }
            catch(Exception ex) { failed=true; }
            finally {
                synchronized(CompanionTasks.this) {
                    runner=null;
                    if(timeout) change(this,"TIMED_OUT",null,null);
                    else if(task.state().equals("CANCEL_REQUESTED")) change(this,"CANCELLED",null,null);
                    else if(failed) change(this,"FAILED",null,"Task execution failed");
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
    }
    public synchronized Task submit(String owner, Request request) {
        if(closed) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        if(request==null || request.message()==null || request.message().isBlank() || request.message().length()>32000
            || (request.role()!=null && !Set.of("TASK","RESEARCH").contains(request.role()))
            || (request.priority()!=null && (request.priority()<0 || request.priority()>100))
            || (request.timeoutSeconds()!=null && (request.timeoutSeconds()<1 || request.timeoutSeconds()>3600))
            || (request.maxAttempts()!=null && request.maxAttempts()!=1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid task request; retries are not safe for tool execution");
        if(!sequences.containsKey(owner) && sequences.size()>=1024) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Owner capacity reached");
        long active=tasks.values().stream().filter(e->!TERMINAL.contains(e.task.state())).count();
        if(active>=128) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Task capacity reached");
        purgeHistory();
        if(tasks.size()>=1000){var oldest=tasks.values().stream().filter(e->TERMINAL.contains(e.task.state())).min(Comparator.comparing(e->e.task.updatedAt()));oldest.ifPresent(e->delete(e.owner,e.task.taskId()));}
        Entry e=new Entry(owner,request);persist(owner,e.task);tasks.put(e.task.taskId(),e);if(plans!=null)plans.register(owner,e.task.taskId(),e.task.state());emit(e,"task.created");
        e.alarm=timer.schedule(()->expire(e),request.timeoutSeconds()==null?120:request.timeoutSeconds(),TimeUnit.SECONDS);
        workers.execute(e); return e.task;
    }
    public synchronized List<Task> list(String owner) { return tasks.values().stream().filter(e->e.owner.equals(owner)).map(e->e.task).sorted(Comparator.comparing(Task::createdAt).reversed()).toList(); }
    private Entry owned(String owner,String id) { Entry e=tasks.get(id); if(e==null || !e.owner.equals(owner)) throw new ResponseStatusException(HttpStatus.NOT_FOUND); return e; }
    public synchronized Task get(String owner,String id) { return owned(owner,id).task; }
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
    private void change(Entry e,String state,String result,String error) {
        if(TERMINAL.contains(e.task.state()))return;
        Task t=e.task; Task next=new Task(t.taskId(),t.role(),state,t.priority(),null,t.createdAt(),Instant.now(),result,error);
        persist(e.owner,next);e.task=next;
        if(plans!=null)plans.taskState(e.owner,next.taskId(),state,false);
        emit(e,"task."+switch(state){case "RUNNING"->"started";case "CANCEL_REQUESTED"->"cancel_requested";default->state.toLowerCase(Locale.ROOT);});
    }
    private void emit(Entry e,String type) {
        long seq=sequences.merge(e.owner,1L,Long::sum);
        var log=events.computeIfAbsent(e.owner,k->new ArrayDeque<>());
        log.add(new Event(UUID.randomUUID().toString(),VERSION,Instant.now(),seq,type,e.task.taskId(),e.task));
        String kind=switch(type){case "task.created"->"ACKNOWLEDGMENT";case "task.started"->"PROGRESS";case "task.completed"->"COMPLETED";case "task.failed"->"FAILED";default->null;};
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
        return Map.of("plans",currentPlans,"contractVersion",VERSION,"serverInstanceId",serverInstanceId,"latestSequence",sequences.getOrDefault(owner,0L),"tasks",list,"activeProcesses",list.stream().filter(t->!TERMINAL.contains(t.state())).count());
    }
    @PreDestroy public void close() {
        synchronized(this){closed=true;workers.shutdownNow();timer.shutdownNow();}
        try{workers.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }
}
