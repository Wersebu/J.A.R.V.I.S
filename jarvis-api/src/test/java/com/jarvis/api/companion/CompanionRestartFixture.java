package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.companion.*;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import com.jarvis.tools.taskplan.DynamicPlanService;
import java.time.Instant;
import java.util.List;
/** Separate JVM writes unfinished state, then exits. No engine exists to retry or clean it up. */
public class CompanionRestartFixture {
    public static void main(String[] args)throws Exception{
        var store=new SQLiteCompanionDocumentStore(true,args[0]);var mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var task=new CompanionTasks.Task(args[1],"TASK","RUNNING",50,null,Instant.now(),Instant.now(),null,null);
        store.put(new CompanionDocumentStore.Document("alice","task",task.taskId(),mapper.writeValueAsString(task),Instant.now()));
        var plans=new DynamicPlanService(store,mapper);plans.register("alice",task.taskId(),"RUNNING");var p=plans.create("alice",task.taskId(),"Interrupted task",List.of("Execute once"));plans.update("alice",task.taskId(),1,p.steps().getFirst().stepId(),"IN_PROGRESS",null,null,null);
    }
}
