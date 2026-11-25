package com.jarvis.tools.taskplan;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.TaskExecutionContext;
import com.jarvis.tools.*;
import com.jarvis.tools.schema.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Model tools use backend task context and never accept owner authority from tool arguments. */
@Service
public class DynamicPlanTool implements JarvisTool,ToolSchemaProvider {
    private final DynamicPlanService plans;
    public DynamicPlanTool(DynamicPlanService plans){this.plans=plans;}
    public String getName(){return "taskplan";}
    public String getDescription(){return "Dynamic executable plan for the current companion task. Use only for complex work, never simple chat. Public actionable steps only; no private reasoning, prompts or secrets. Update by exact revision; completed steps require backend executionEvidenceRef from a successful real tool call after IN_PROGRESS. Logical tools: plan_create, plan_get, plan_add_step, plan_update_step, plan_reorder, plan_complete.";}
    private ToolArgumentDefinition arg(String name,String type,boolean required,String description){return new ToolArgumentDefinition(name,type,required,description);}
    private ToolOperationDefinition op(String name,List<ToolArgumentDefinition> args){return new ToolOperationDefinition(name,getDescription(),args,false,ToolSafetyLevel.READ);}
    public ToolDefinition definition(){return new ToolDefinition(getName(),getDescription(),List.of(
        op("CREATE",List.of(arg("title","string",true,"Public task goal"),new ToolArgumentDefinition("steps",true,ToolJsonSchema.arrayOf(ToolJsonSchema.string("Executable public action"),"1..100 step titles")))),
        op("GET",List.of()),
        op("ADD_STEP",List.of(arg("expectedRevision","integer",true,"Current revision"),arg("title","string",true,"New executable action"),arg("description","string",false,"Optional public description"),arg("position","integer",true,"Zero-based insertion position"))),
        op("UPDATE_STEP",List.of(arg("expectedRevision","integer",true,"Current revision"),arg("stepId","string",true,"Step UUID"),arg("status","string",true,"IN_PROGRESS, COMPLETED, FAILED, SKIPPED or CANCELLED"),arg("resultSummary","string",false,"Verified public result or skip reason"),arg("error","string",false,"Public error"),arg("evidenceRef","string",false,"Backend-issued executionEvidenceRef; required for COMPLETED"))),
        op("REORDER",List.of(arg("expectedRevision","integer",true,"Current revision"),new ToolArgumentDefinition("stepIds",true,ToolJsonSchema.arrayOf(ToolJsonSchema.string("Step UUID"),"Every step exactly once")))),
        op("COMPLETE",List.of(arg("expectedRevision","integer",true,"Current revision")))));}
    public ToolResult execute(ToolRequest request){
        try{String owner=CurrentUserContext.requireAuthenticatedUserId(),task=TaskExecutionContext.current();if(task==null)throw new IllegalArgumentException("Task planning is available only in a backend task context");if(request.arguments().containsKey("taskId")&&!task.equals(text(request,"taskId")))throw new IllegalArgumentException("Cross-task modification rejected");
            var args=request.arguments();DynamicPlan plan=switch(request.operation().toUpperCase(Locale.ROOT)){
                case "CREATE"->plans.create(owner,task,text(request,"title"),list(args.get("steps")));
                case "GET"->plans.find(owner,task).orElse(null);
                case "ADD_STEP"->plans.add(owner,task,number(request,"expectedRevision"),text(request,"title"),text(request,"description"),Math.toIntExact(number(request,"position")));
                case "UPDATE_STEP"->plans.update(owner,task,number(request,"expectedRevision"),text(request,"stepId"),text(request,"status"),text(request,"resultSummary"),text(request,"error"),text(request,"evidenceRef"));
                case "REORDER"->plans.reorder(owner,task,number(request,"expectedRevision"),list(args.get("stepIds")));
                case "COMPLETE"->plans.complete(owner,task,number(request,"expectedRevision"));
                default->throw new IllegalArgumentException("Unsupported task planning operation");};
            return new ToolResult(true,getName(),request.operation(),request.requestId(),request.conversationId(),!request.operation().equalsIgnoreCase("GET"),List.of(),"Use real tools for executable work; never infer elapsed percentage from step counts",plan==null?Map.of("exists",false):Map.of("plan",plan),"","",false,"");
        }catch(RuntimeException e){return new ToolResult(false,getName(),request.operation(),request.requestId(),request.conversationId(),false,List.of(),"Plan operation rejected",Map.of(),"PLAN_REJECTED",e instanceof IllegalArgumentException?e.getMessage():"Planning operation unavailable",false,"");}
    }
    private String text(ToolRequest request,String key){Object v=request.arguments().get(key);return v==null?null:String.valueOf(v);}
    private long number(ToolRequest request,String key){try{return Long.parseLong(String.valueOf(request.arguments().get(key)));}catch(Exception e){throw new IllegalArgumentException("Missing/invalid integer: "+key);}}
    private List<String> list(Object value){if(!(value instanceof List<?> list))throw new IllegalArgumentException("Expected an array");return list.stream().map(String::valueOf).toList();}
}
