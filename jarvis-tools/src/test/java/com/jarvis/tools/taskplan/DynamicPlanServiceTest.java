package com.jarvis.tools.taskplan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.companion.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class DynamicPlanServiceTest {
    private DynamicPlanService service(){var service=new DynamicPlanService(new InMemoryCompanionStore(),new ObjectMapper());service.register("alice","task-a","RUNNING");service.register("alice","task-b","RUNNING");return service;}
    @Test void createInsertReorderAndEvidenceGatedTransitions(){
        var service=service();var plan=service.create("alice","task-a","Inspect source",List.of("Read source","Verify result"));assertEquals(1,plan.revision());
        assertThrows(IllegalArgumentException.class,()->service.create("alice","task-a","Replace",List.of("bad")));
        plan=service.add("alice","task-a",1,"Read tests","Useful verification",1);assertEquals(3,plan.totalStepCount());assertEquals("Read tests",plan.steps().get(1).title());
        assertThrows(IllegalArgumentException.class,()->service.add("alice","task-a",1,"Stale",null,0));
        String first=plan.steps().getFirst().stepId();plan=service.update("alice","task-a",2,first,"IN_PROGRESS",null,null,null);
        assertThrows(IllegalArgumentException.class,()->service.update("alice","task-a",3,first,"COMPLETED","claimed",null,UUID.randomUUID().toString()));
        var failed=service.recordEvidence("alice","task-a","pc","READ",false);assertThrows(IllegalArgumentException.class,()->service.update("alice","task-a",3,first,"COMPLETED",null,null,failed.evidenceRef()));
        var receipt=service.recordEvidence("alice","task-a","pc","READ",true);plan=service.update("alice","task-a",3,first,"COMPLETED","Source read",null,receipt.evidenceRef());assertEquals(1,plan.completedStepCount());
        assertThrows(IllegalArgumentException.class,()->service.update("alice","task-a",4,first,"IN_PROGRESS",null,null,null));
        assertThrows(IllegalArgumentException.class,()->service.complete("alice","task-a",4));
        var order=new ArrayList<>(plan.steps().stream().map(DynamicPlan.Step::stepId).toList());Collections.reverse(order);plan=service.reorder("alice","task-a",4,order);assertEquals(0,plan.steps().getFirst().position());
        String next=plan.steps().getFirst().stepId();assertThrows(IllegalArgumentException.class,()->service.update("alice","task-a",5,next,"SKIPPED",null,null,null));
        plan=service.update("alice","task-a",5,next,"SKIPPED","Not needed",null,null);String last=plan.steps().get(1).stepId();plan=service.update("alice","task-a",6,last,"SKIPPED","No change needed",null,null);plan=service.complete("alice","task-a",7);assertEquals("COMPLETED",plan.status());assertEquals(8,plan.revision());
    }
    @Test void ownershipCrossTaskEvidenceAndCancellation(){
        var service=service();var p=service.create("alice","task-a","Task A",List.of("Read"));service.create("alice","task-b","Task B",List.of("Check"));assertTrue(service.find("bob","task-a").isEmpty());
        assertThrows(IllegalArgumentException.class,()->service.add("bob","task-a",1,"Inject",null,0));
        p=service.update("alice","task-a",1,p.steps().getFirst().stepId(),"IN_PROGRESS",null,null,null);String step=p.steps().getFirst().stepId();var foreign=service.recordEvidence("alice","task-b","pc","READ",true);
        assertThrows(IllegalArgumentException.class,()->service.update("alice","task-a",2,step,"COMPLETED",null,null,foreign.evidenceRef()));
        service.taskState("alice","task-a","CANCEL_REQUESTED",false);assertEquals("CANCELLED",service.find("alice","task-a").orElseThrow().steps().getFirst().status());
        assertThrows(IllegalArgumentException.class,()->service.add("alice","task-a",3,"Late",null,0));assertEquals("ACTIVE",service.find("alice","task-b").orElseThrow().status());
    }
    @Test void independentConcurrentPlansAndUnsafeLabels() throws Exception {
        var service=service();assertThrows(IllegalArgumentException.class,()->service.create("alice","task-a","password: do-not-store",List.of("Read")));
        service.create("alice","task-a","Task A",List.of("Start"));service.create("alice","task-b","Task B",List.of("Start"));
        try(var pool=Executors.newFixedThreadPool(2)){var jobs=new ArrayList<Future<?>>();for(String id:List.of("task-a","task-b"))jobs.add(pool.submit(()->{for(int i=0;i<8;i++){var plan=service.find("alice",id).orElseThrow();service.add("alice",id,plan.revision(),"Action "+i,null,plan.steps().size());}}));for(var f:jobs)f.get(3,TimeUnit.SECONDS);}
        assertEquals(9,service.find("alice","task-a").orElseThrow().revision());assertEquals(9,service.find("alice","task-b").orElseThrow().totalStepCount());
    }
    @Test void explicitFailedStepRetryPreservesHistoryAndRequiresFreshEvidence(){
        var service=service();var plan=service.create("alice","task-a","Verify work",List.of("Run check"));String step=plan.steps().getFirst().stepId();service.update("alice","task-a",1,step,"IN_PROGRESS",null,null,null);var old=service.recordEvidence("alice","task-a","fixture","CHECK",true);service.update("alice","task-a",2,step,"FAILED",null,"A later verification failed",null);
        assertThrows(IllegalArgumentException.class,()->service.complete("alice","task-a",3));service.update("alice","task-a",3,step,"IN_PROGRESS",null,null,null);
        assertThrows(IllegalArgumentException.class,()->service.update("alice","task-a",4,step,"COMPLETED",null,null,old.evidenceRef()));var fresh=service.recordEvidence("alice","task-a","fixture","VERIFY",true);service.update("alice","task-a",4,step,"COMPLETED","Verified after retry",null,fresh.evidenceRef());assertEquals("COMPLETED",service.complete("alice","task-a",5).status());assertTrue(service.revisions("alice","task-a").stream().anyMatch(p->p.steps().getFirst().status().equals("FAILED")));
    }

}
