package com.jarvis.common.companion;

import com.jarvis.common.auth.CurrentUserContext;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Executing tools/helpers only; task lifecycle and inference admission have separate authorities. */
public class ActivityTracker {
    private record Activity(String owner,String kind,String requestId,String taskId,String operation,Instant started){}
    private final Map<UUID,Activity> active=new ConcurrentHashMap<>();
    public Scope start(String kind,String requestId,String operation){
        return start(kind,requestId,operation,TaskExecutionContext.current());
    }
    public Scope start(String kind,String requestId,String operation,String taskId){
        UUID id=UUID.randomUUID();active.put(id,new Activity(CurrentUserContext.requiredUserId(),kind,requestId,taskId,operation,Instant.now()));
        return ()->active.remove(id);
    }
    public Map<String,Object> snapshot(String owner){
        var selected=active.values().stream().filter(a->a.owner().equals(owner)).toList();
        var details=new ArrayList<Map<String,Object>>();
        for(var a:selected){var m=new LinkedHashMap<String,Object>();m.put("kind",a.kind());m.put("requestId",a.requestId());m.put("taskId",a.taskId());m.put("operation",a.operation());m.put("startedAt",a.started());details.add(m);}
        return Map.of("activeInteractiveRequests",selected.stream().filter(a->a.kind().equals("INTERACTIVE")).count(),"activeToolExecutions",selected.stream().filter(a->a.kind().equals("TOOL")).count(),
            "activeSynchronousAgents",selected.stream().filter(a->a.kind().equals("AGENT")).count(),"activeCodingTasks",selected.stream().filter(a->a.kind().equals("CODING_TASK")).count(),"activities",details);
    }
    public interface Scope extends AutoCloseable{@Override void close();}
}
