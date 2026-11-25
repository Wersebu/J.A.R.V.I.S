package com.jarvis.tools.runtime;

import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.*;
import com.jarvis.tools.*;
import com.jarvis.tools.schema.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** True asynchronous delegation. A successful SUBMIT is backed by a persistent task receipt. */
@Service
public class CompanionDelegationTool implements JarvisTool,ToolSchemaProvider {
    private final ObjectProvider<CompanionTaskSubmission> submissions;
    public CompanionDelegationTool(ObjectProvider<CompanionTaskSubmission> submissions){this.submissions=submissions;}
    public String getName(){return "companion";}
    public String getDescription(){return "Delegate independent work asynchronously and continue the conversation. SUBMIT returns a real taskId; it does not wait for results. GET retrieves current status/result, CANCEL interrupts, INSTRUCT queues a follow-up after the current execution. Use agent__run only for immediate dependent results. Never claim a task was started without a successful receipt.";}
    private ToolArgumentDefinition arg(String name,boolean required,String description){return new ToolArgumentDefinition(name,"string",required,description);}
    public ToolDefinition definition(){return new ToolDefinition(getName(),getDescription(),List.of(
        new ToolOperationDefinition("SUBMIT",getDescription(),List.of(arg("message",true,"Self-contained goal, relevant context and expected result"),arg("role",false,"TASK or RESEARCH")),false,ToolSafetyLevel.READ),
        new ToolOperationDefinition("GET","Retrieve a delegated task",List.of(arg("taskId",true,"Backend-issued task identifier")),false,ToolSafetyLevel.READ),
        new ToolOperationDefinition("CANCEL","Cancel a delegated task",List.of(arg("taskId",true,"Backend-issued task identifier")),false,ToolSafetyLevel.READ),
        new ToolOperationDefinition("INSTRUCT","Queue a follow-up instruction for a task, preserving execution isolation",List.of(arg("taskId",true,"Backend-issued task identifier"),arg("message",true,"Additional instruction")),false,ToolSafetyLevel.READ)));
    }
    public ToolResult execute(ToolRequest r){
        try{
            var service=submissions.getIfAvailable();if(service==null)throw new IllegalStateException("Async delegation unavailable");
            String owner=CurrentUserContext.requireAuthenticatedUserId();String op=r.operation().toUpperCase(Locale.ROOT);
            if(TaskExecutionContext.current()!=null&&op.equals("SUBMIT"))throw new IllegalArgumentException("Background tasks cannot recursively delegate; finish current task first");
            Map<String,Object> result=switch(op){
                case "SUBMIT"->service.submit(owner,r.conversationId(),text(r,"message"),r.arguments().containsKey("role")?text(r,"role"):"TASK",r.requestId());
                case "GET"->service.get(owner,text(r,"taskId"));case "CANCEL"->service.cancel(owner,text(r,"taskId"));
                case "INSTRUCT"->service.instruct(owner,text(r,"taskId"),text(r,"message"));default->throw new IllegalArgumentException("Unknown delegation operation");};
            return new ToolResult(true,getName(),op,r.requestId(),r.conversationId(),!op.equals("GET"),List.of(),"Authoritative backend task receipt; QUEUED means accepted, not completed",result,"","",false,"");
        }catch(RuntimeException e){return new ToolResult(false,getName(),r.operation(),r.requestId(),r.conversationId(),false,List.of(),"Delegation rejected",Map.of(),"DELEGATION_REJECTED","No new task receipt was issued; inspect task status before retrying",false,"");}
    }
    private String text(ToolRequest r,String name){return String.valueOf(r.arguments().getOrDefault(name,""));}
}
