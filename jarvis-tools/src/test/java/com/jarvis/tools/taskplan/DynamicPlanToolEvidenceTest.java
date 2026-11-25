package com.jarvis.tools.taskplan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.*;
import com.jarvis.tools.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class DynamicPlanToolEvidenceTest {
    @Test void nativeManagerIssuesTrustedReceiptAndRejectsCrossTaskArguments(){
        var plans=new DynamicPlanService(new InMemoryCompanionStore(),new ObjectMapper());plans.register("alice","task-a","RUNNING");var planTool=new DynamicPlanTool(plans);
        JarvisTool real=new JarvisTool(){public String getName(){return "fixture";}public String getDescription(){return "Test action";}public ToolResult execute(ToolRequest r){return new ToolResult(true,getName(),r.operation(),r.requestId(),r.conversationId(),false,List.of(),"Verified",Map.of(),"","",false,"");}};
        var manager=new DefaultToolManager(List.of(planTool,real));ReflectionTestUtils.setField(manager,"companionPlans",plans);
        CurrentUserContext.runAs("alice",()->TaskExecutionContext.run("task-a",()->{
            var create=manager.execute(new ToolRequest("taskplan","CREATE","c","r","","",Map.of("title","Read fixture","steps",List.of("Read action"))));assertTrue(create.success());var p=plans.find("alice","task-a").orElseThrow();String step=p.steps().getFirst().stepId();
            assertTrue(manager.execute(new ToolRequest("taskplan","UPDATE_STEP","c","r","","",Map.of("expectedRevision",1,"stepId",step,"status","IN_PROGRESS"))).success());
            var receipt=manager.execute(new ToolRequest("fixture","READ","c","r","","",Map.of()));assertNotNull(receipt.data().get("executionEvidenceRef"));
            assertTrue(manager.execute(new ToolRequest("taskplan","UPDATE_STEP","c","r","","",Map.of("expectedRevision",2,"stepId",step,"status","COMPLETED","evidenceRef",receipt.data().get("executionEvidenceRef")))).success());
            assertFalse(manager.execute(new ToolRequest("taskplan","ADD_STEP","c","r","","",Map.of("taskId","task-b","expectedRevision",3,"title","Cross task","position",1))).success());
            assertEquals(3,plans.find("alice","task-a").orElseThrow().revision());
        }));assertNull(TaskExecutionContext.current());
    }
    @Test void asyncStatusAndStringExitCodesAreNotCompletionEvidence(){
        var plans=new DynamicPlanService(new InMemoryCompanionStore(),new ObjectMapper());plans.register("alice","task-a","RUNNING");var plan=plans.create("alice","task-a","Verify execution",List.of("Run verification"));String step=plan.steps().getFirst().stepId();plans.update("alice","task-a",1,step,"IN_PROGRESS",null,null,null);
        JarvisTool fixture=new JarvisTool(){public String getName(){return "fixture";}public String getDescription(){return "Async test";}public ToolResult execute(ToolRequest r){return new ToolResult(true,getName(),r.operation(),r.requestId(),r.conversationId(),false,List.of(),"Accepted",r.operation().equals("ASYNC")?Map.of("status","RUNNING"):Map.of("exitCode","2"),"","",false,"");}};
        var manager=new DefaultToolManager(List.of(fixture));ReflectionTestUtils.setField(manager,"companionPlans",plans);
        CurrentUserContext.runAs("alice",()->TaskExecutionContext.run("task-a",()->{for(String op:List.of("ASYNC","EXIT")){var receipt=manager.execute(new ToolRequest("fixture",op,"c","r","","",Map.of()));assertThrows(IllegalArgumentException.class,()->plans.update("alice","task-a",2,step,"COMPLETED",null,null,receipt.data().get("executionEvidenceRef").toString()));}}));assertEquals("IN_PROGRESS",plans.find("alice","task-a").orElseThrow().steps().getFirst().status());
    }

}
