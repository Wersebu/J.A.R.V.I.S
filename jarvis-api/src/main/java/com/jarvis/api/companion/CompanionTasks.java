package com.jarvis.api.companion;

import com.jarvis.api.service.ChatService;
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
    private static final String VERSION = "1.3.0";
    public static String version() { return VERSION; }
    public record Request(String message, String role, Integer priority, Integer timeoutSeconds, Integer maxAttempts) {}
    public record Task(String taskId, String role, String state, int priority, Double progress,
                       Instant createdAt, Instant updatedAt, String result, String error) {}
    public record Event(String eventId, String protocolVersion, Instant timestamp, long sequence,
                        String type, String taskId, Object payload) {}
    public record Replay(boolean resetRequired, List<Event> events, long latestSequence) {}
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
        public int compareTo(Entry other) { int n=Integer.compare(task.priority(),other.task.priority()); return n==0?Long.compare(position,other.position):n; }
        public void run() {
            synchronized(CompanionTasks.this) {
                if(TERMINAL.contains(task.state())) return;
                if(System.nanoTime()>=deadline) { timeout=true; change(this,"TIMED_OUT",null,null); return; }
                runner=Thread.currentThread(); change(this,"RUNNING",null,null);
            }
            String[] result={null}; boolean failed=false;
            try { CurrentUserContext.runAs(owner, () -> com.jarvis.common.ai.InferencePriorityContext.runBackground(() -> result[0]=chat.chat(new ChatRequest("companion-"+task.taskId(),message)).response())); }
            catch(Exception ex) { failed=true; }
            finally {
                synchronized(CompanionTasks.this) {
                    runner=null;
                    if(timeout) change(this,"TIMED_OUT",null,null);
                    else if(task.state().equals("CANCEL_REQUESTED")) change(this,"CANCELLED",null,null);
                    else if(failed) change(this,"FAILED",null,"Task execution failed");
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
        if(tasks.size()>=1000) {
            var it=tasks.values().iterator();
            while(it.hasNext() && tasks.size()>=1000) if(TERMINAL.contains(it.next().task.state())) it.remove();
        }
        Entry e=new Entry(owner,request); tasks.put(e.task.taskId(),e); emit(e,"task.created");
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
        Task t=e.task; e.task=new Task(t.taskId(),t.role(),state,t.priority(),null,t.createdAt(),Instant.now(),result,error);
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
        return new Replay(reset,reset?List.of():log.stream().filter(e->e.sequence()>after).toList(),last);
    }
    public synchronized Map<String,Object> state(String owner) {
        List<Task> list=list(owner);
        return Map.of("contractVersion",VERSION,"serverInstanceId",serverInstanceId,"latestSequence",sequences.getOrDefault(owner,0L),"tasks",list,"activeProcesses",list.stream().filter(t->!TERMINAL.contains(t.state())).count());
    }
    @PreDestroy public synchronized void close() { closed=true; workers.shutdownNow(); timer.shutdownNow(); }
}
