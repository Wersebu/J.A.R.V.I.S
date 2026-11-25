package com.jarvis.api.companion;

import com.jarvis.common.companion.CompanionTaskSubmission;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CompanionTaskSubmissionAdapter implements CompanionTaskSubmission {
    private final CompanionTasks tasks;
    public CompanionTaskSubmissionAdapter(CompanionTasks tasks){this.tasks=tasks;}
    private Map<String,Object> receipt(CompanionTasks.Task t){var m=new LinkedHashMap<String,Object>();m.put("taskId",t.taskId());m.put("state",t.state());m.put("status",t.state());m.put("pending",t.state().equals("QUEUED"));m.put("running",t.state().equals("RUNNING"));m.put("conversationId",t.conversationId());m.put("executionConversationId",t.executionConversationId());m.put("result",t.result());m.put("error",t.error());m.values().removeIf(java.util.Objects::isNull);return Map.copyOf(m);}
    public Map<String,Object> submit(String owner,String conversation,String message,String role,String key){return receipt(tasks.submit(owner,new CompanionTasks.Request(message,role,50,600,1),conversation,key));}
    public Map<String,Object> get(String owner,String id){return receipt(tasks.get(owner,id));}
    public Map<String,Object> cancel(String owner,String id){return receipt(tasks.cancel(owner,id));}
    public Map<String,Object> instruct(String owner,String id,String message){return receipt(tasks.instruct(owner,id,message));}
}
