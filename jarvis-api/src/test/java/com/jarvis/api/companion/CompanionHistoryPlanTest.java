package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.*;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.companion.*;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import com.jarvis.tools.taskplan.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompanionHistoryPlanTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    @Test void restoreAcrossJvmWithoutRepeatingExternalActionsAndDelete()throws Exception {
        Path file=temp.resolve("history.db");String id=UUID.randomUUID().toString();
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",System.getProperty("java.class.path"),CompanionRestartFixture.class.getName(),file.toString(),id).redirectErrorStream(true).start();
        assertTrue(child.waitFor(10,TimeUnit.SECONDS));assertEquals(0,child.exitValue(),new String(child.getInputStream().readAllBytes()));
        var chat=mock(ChatService.class);var store=new SQLiteCompanionDocumentStore(true,file.toString());var plans=new DynamicPlanService(store,mapper);
        try(var tasks=new CompanionTasks(chat,1)){
            tasks.configureHistory(store,mapper,plans,30);assertTrue(tasks.durableHistory());assertEquals("FAILED",tasks.get("alice",id).state());assertTrue(tasks.get("alice",id).error().contains("restart"));assertEquals("INTERRUPTED",tasks.plan("alice",id).status());assertEquals("CANCELLED",tasks.plan("alice",id).steps().getFirst().status());verifyNoInteractions(chat);
            assertThrows(ResponseStatusException.class,()->tasks.plan("bob",id));assertThrows(ResponseStatusException.class,()->tasks.delete("bob",id));tasks.delete("alice",id);assertTrue(tasks.list("alice").isEmpty());assertTrue(plans.revisions("alice",id).isEmpty());
        }
        store.close();
        var reopened=new SQLiteCompanionDocumentStore(true,file.toString());try(var tasks=new CompanionTasks(chat,1)){tasks.configureHistory(reopened,mapper,new DynamicPlanService(reopened,mapper),30);assertTrue(tasks.list("alice").isEmpty());}finally{reopened.close();}
    }
    @Test void livePlanEventsSnapshotRevisionsCancellationAndReplay()throws Exception {
        var store=new InMemoryCompanionStore();var plans=new DynamicPlanService(store,mapper);var chat=mock(ChatService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(i->{String id=TaskExecutionContext.current();plans.create("alice",id,"Inspect source",List.of("Read source"));entered.countDown();release.await();return new ChatResponse("done");});
        try(var tasks=new CompanionTasks(chat,1)){
            tasks.configureHistory(store,mapper,plans,30);var task=tasks.submit("alice",new CompanionTasks.Request("work","TASK",50,10,1));assertTrue(entered.await(2,TimeUnit.SECONDS));var p=tasks.plan("alice",task.taskId());p=plans.add("alice",task.taskId(),1,"Verify source",null,1);assertEquals(2,p.revision());
            int before=tasks.replay("alice",0).events().size();assertThrows(IllegalArgumentException.class,()->plans.add("alice",task.taskId(),1,"Old revision",null,0));assertEquals(before,tasks.replay("alice",0).events().size());
            var snapshot=tasks.state("alice");assertEquals(1,((List<?>)snapshot.get("plans")).size());var replay=tasks.replay("alice",0);assertEquals(replay.events(),tasks.replay("alice",0).events());
            var planEvent=replay.events().stream().filter(e->e.type().equals("task.plan.updated")).findFirst().orElseThrow();var tree=mapper.valueToTree(planEvent.payload());assertEquals(task.taskId(),tree.path("taskId").asText());assertEquals("RUNNING",tree.path("state").asText());assertEquals(2,tree.path("plan").path("revision").asInt());
            assertThrows(ResponseStatusException.class,()->tasks.delete("alice",task.taskId()));tasks.cancel("alice",task.taskId());release.countDown();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!tasks.get("alice",task.taskId()).state().equals("CANCELLED")&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals("CANCELLED",tasks.plan("alice",task.taskId()).status());tasks.delete("alice",task.taskId());assertTrue(tasks.state("alice").get("plans") instanceof List<?> list&&list.isEmpty());assertTrue(tasks.replay("alice",0).resetRequired());
        }finally{release.countDown();}
    }
    @Test void unfinishedPlanCannotBecomeSuccessfulTask()throws Exception {
        var store=new InMemoryCompanionStore();var plans=new DynamicPlanService(store,mapper);var chat=mock(ChatService.class);
        when(chat.chat(any())).thenAnswer(i->{plans.create("alice",TaskExecutionContext.current(),"Actual work",List.of("Must verify"));return new ChatResponse("I claim success");});
        try(var tasks=new CompanionTasks(chat,1)){tasks.configureHistory(store,mapper,plans,30);var task=tasks.submit("alice",new CompanionTasks.Request("work","TASK",50,5,1));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!tasks.get("alice",task.taskId()).state().equals("FAILED")&&System.nanoTime()<end)Thread.sleep(5);assertEquals("FAILED",tasks.get("alice",task.taskId()).state());assertNull(tasks.get("alice",task.taskId()).result());}
    }
    @Test void deadlineCancelsPlanAndRetentionDoesNotRestoreExpiredHistory()throws Exception {
        var store=new InMemoryCompanionStore();var plans=new DynamicPlanService(store,mapper);var chat=mock(ChatService.class);
        when(chat.chat(any())).thenAnswer(i->{plans.create("alice",TaskExecutionContext.current(),"Timed work",List.of("Execute"));Thread.sleep(10000);return new ChatResponse("late");});
        try(var tasks=new CompanionTasks(chat,1)){
            tasks.configureHistory(store,mapper,plans,30);var task=tasks.submit("alice",new CompanionTasks.Request("work","TASK",50,1,1));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!tasks.get("alice",task.taskId()).state().equals("TIMED_OUT")&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals("TIMED_OUT",tasks.get("alice",task.taskId()).state());assertEquals("CANCELLED",tasks.plan("alice",task.taskId()).steps().getFirst().status());
        }
        var old=java.time.Instant.now().minusSeconds(40L*86400);String id=UUID.randomUUID().toString();var terminal=new CompanionTasks.Task(id,"TASK","COMPLETED",50,null,old,old,"old result",null);store.put(new CompanionDocumentStore.Document("bob","task",id,mapper.writeValueAsString(terminal),old));
        try(var tasks=new CompanionTasks(mock(ChatService.class),1)){tasks.configureHistory(store,mapper,new DynamicPlanService(store,mapper),30);assertTrue(tasks.list("bob").isEmpty());assertTrue(store.find("bob","task",id).isEmpty());}
    }
    @Test void exclusiveWriterLockPreventsLiveHistoryRecoveryByAnotherInstance(){
        Path path=temp.resolve("exclusive.db");try(var store=new SQLiteCompanionDocumentStore(true,path.toString())){assertThrows(IllegalStateException.class,()->new SQLiteCompanionDocumentStore(true,path.toString()));assertTrue(store.durable());}
        try(var reopened=new SQLiteCompanionDocumentStore(true,path.toString())){assertTrue(reopened.durable());}
    }

}
