package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.companion.*;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.tools.taskplan.DynamicPlanService;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SemanticTaskLifecycleTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private CompanionTasks engine(ChatService chat,CompanionDocumentStore store){var tasks=new CompanionTasks(chat,3);tasks.configureHistory(store,mapper,new DynamicPlanService(store,mapper),30);var delivery=mock(TaskResultDelivery.class);when(delivery.deliver(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(TaskResultDelivery.Status.DELIVERED);when(delivery.synthesize(anyString(),anyString(),anyString())).thenReturn("Zebrano niezależne wyniki.");when(delivery.synthesize(anyString(),anyString(),anyString(),any())).thenReturn("Zebrano niezależne wyniki.");ReflectionTestUtils.setField(tasks,"resultDelivery",delivery);return tasks;}
    private void await(CompanionTasks tasks,String root)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);while(Set.of("RUNNING","WAITING","QUEUED").contains(tasks.get("alice",root).state())&&System.nanoTime()<end)Thread.sleep(10);assertFalse(Set.of("RUNNING","WAITING","QUEUED").contains(tasks.get("alice",root).state()),tasks.get("alice",root).toString());}
    @Test void independentLookupWorkersCannotSpawnUnplannedHelpers()throws Exception{
        var chat=mock(ChatService.class);
        when(chat.chat(any())).thenAnswer(i->{assertFalse(AgentScope.current().tools().contains("companion__submit"));return new ChatResponse("Confirmed lookup result");});
        try(var tasks=engine(chat,new InMemoryCompanionStore())){
            var root=tasks.submitOrchestration("alice","source","bounded","Independent lookup",List.of(new AutonomousOrchestration.Agent("a","Lookup","Read",List.of("web__search_web"),List.of())),Set.of("web__search_web"),false);
            await(tasks,root.taskId());
            assertEquals("COMPLETED",tasks.get("alice",root.taskId()).state());
            assertEquals(2,tasks.list("alice").size());
        }
    }
    @Test void oneFailedWorkerDoesNotStopSiblingAndRootEndsPartialWithLegacyTerminalState()throws Exception{
        var chat=mock(ChatService.class);when(chat.chat(any())).thenAnswer(i->{if(((com.jarvis.common.dto.ChatRequest)i.getArgument(0)).message().equals("offline"))throw new IllegalStateException("Integration unavailable");assertNotNull(AgentScope.current());return new ChatResponse("Zapisany pełny wynik");});
        try(var store=new SQLiteCompanionDocumentStore(true,temp.resolve("history.db").toString());var tasks=engine(chat,store)){
            var root=tasks.submitOrchestration("alice","source","request","Niezależne cele",List.of(new AutonomousOrchestration.Agent("one","Dostępne źródło","online",List.of(),List.of()),new AutonomousOrchestration.Agent("two","Niedostępna integracja","offline",List.of(),List.of())),Set.of());
            await(tasks,root.taskId());var child=tasks.list("alice").stream().filter(t->!t.taskId().equals(root.taskId())).findFirst().orElseThrow();tasks.record("alice","agent.tool.started",child.taskId(),Map.of("conversationId",child.executionConversationId(),"tool","fixture"));var event=tasks.replay("alice",0).events().stream().filter(e->e.type().equals("agent.tool.started")).findFirst().orElseThrow();assertEquals("source",((Map<?,?>)event.payload()).get("conversationId"));assertEquals(child.executionConversationId(),((Map<?,?>)event.payload()).get("executionConversationId"));assertEquals("PARTIALLY_COMPLETED",tasks.get("alice",root.taskId()).state());assertEquals("FAILED",tasks.wire("alice",tasks.get("alice",root.taskId())).get("state"));assertEquals("FAILED",tasks.plan("alice",root.taskId()).status());
            assertEquals(1,tasks.list("alice").stream().filter(t->t.state().equals("COMPLETED")).count());assertEquals("Zebrano niezależne wyniki.",tasks.get("alice",root.taskId()).result());assertTrue(tasks.list("alice").stream().filter(t->!t.taskId().equals(root.taskId())).anyMatch(t->"Zapisany pełny wynik".equals(t.result())));
            var delivery=(TaskResultDelivery)ReflectionTestUtils.getField(tasks,"resultDelivery");verify(delivery).synthesize(eq("alice"),eq("source"),anyString(),any());
            assertEquals(1,tasks.replay("alice",0).events().stream().filter(e->e.type().equals("assistant.message.created")).count());
        }
    }
    @Test void modelDagWaitsForActualDependencyAndDynamicHelperIsBoundedAndTracked()throws Exception{
        var chat=mock(ChatService.class);var seen=new CopyOnWriteArrayList<String>();var reference=new java.util.concurrent.atomic.AtomicReference<CompanionTasks>();
        when(chat.chat(any())).thenAnswer(i->{String goal=((com.jarvis.common.dto.ChatRequest)i.getArgument(0)).message();seen.add(goal);if(goal.equals("first")){var parent=TaskExecutionContext.current();var helper=reference.get().submitAdditional("alice",parent,new CompanionTasks.Request("extra","TASK",50,30,1,"Dodatkowe sprawdzenie",List.of(),null,null));assertNotNull(helper.taskId());}return new ChatResponse("Wynik: "+goal);});
        try(var tasks=engine(chat,new InMemoryCompanionStore())){reference.set(tasks);var root=tasks.submitOrchestration("alice","source","dag","Zależne cele",List.of(new AutonomousOrchestration.Agent("a","Etap pierwszy","first",List.of(),List.of()),new AutonomousOrchestration.Agent("b","Analiza wyniku","second",List.of(),List.of("a"))),Set.of());await(tasks,root.taskId());assertEquals("COMPLETED",tasks.get("alice",root.taskId()).state());assertEquals(4,tasks.list("alice").size());assertTrue(seen.stream().anyMatch(s->s.startsWith("second")&&s.contains("Wynik: first")));assertEquals(3,tasks.plan("alice",root.taskId()).steps().size());assertEquals("COMPLETED",tasks.plan("alice",root.taskId()).status());}
    }
    @Test void interruptedAggregatePrefixIsRecoveredWithoutRepeatingInference()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var chat=mock(ChatService.class);
        when(chat.chat(any())).thenAnswer(i->{entered.countDown();release.await(3,TimeUnit.SECONDS);return new ChatResponse("Wynik agenta");});
        var store=new InMemoryCompanionStore();try(var tasks=engine(chat,store)){
            var root=tasks.submitOrchestration("alice","source","checkpoint","Work",List.of(new AutonomousOrchestration.Agent("a","Task","goal",List.of(),List.of())),Set.of());assertTrue(entered.await(2,TimeUnit.SECONDS));
            String id="task-group/"+tasks.info("alice",root.taskId()).get("groupId");
            store.put(new CompanionDocumentStore.Document("alice","task-stream",id,mapper.writeValueAsString(Map.of("text","Zachowany fragment","chunks",2)),java.time.Instant.now()));
            assertEquals(1,((List<?>)tasks.state("alice").get("streams")).size());assertTrue(((List<?>)tasks.state("bob").get("streams")).isEmpty());
            var delivery=mock(TaskResultDelivery.class);when(delivery.deliver(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(TaskResultDelivery.Status.DELIVERED);ReflectionTestUtils.setField(tasks,"resultDelivery",delivery);
            release.countDown();await(tasks,root.taskId());assertTrue(tasks.get("alice",root.taskId()).result().startsWith("Zachowany fragment"));assertTrue(tasks.get("alice",root.taskId()).result().contains("Wynik agenta"));assertEquals("PARTIALLY_COMPLETED",tasks.get("alice",root.taskId()).state());assertTrue(tasks.list("alice").stream().anyMatch(t->"Wynik agenta".equals(t.result())));verify(delivery,never()).synthesize(anyString(),anyString(),anyString(),any());
        }finally{release.countDown();}
    }

    @Test void durableJournalRetainsExactEventIdentitiesAcrossRestart()throws Exception{
        Path path=temp.resolve("journal.db");var chat=mock(ChatService.class);when(chat.chat(any())).thenReturn(new ChatResponse("wynik"));List<CompanionTasks.Event> original;
        try(var store=new SQLiteCompanionDocumentStore(true,path.toString());var tasks=engine(chat,store)){var t=tasks.submit("alice",new CompanionTasks.Request("work","TASK",50,30,1));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!tasks.get("alice",t.taskId()).state().equals("COMPLETED")&&System.nanoTime()<end)Thread.sleep(10);original=tasks.replay("alice",0).events();}
        try(var store=new SQLiteCompanionDocumentStore(true,path.toString());var tasks=engine(chat,store)){assertEquals(original.stream().map(CompanionTasks.Event::eventId).toList(),tasks.replay("alice",0).events().stream().map(CompanionTasks.Event::eventId).toList());assertTrue(tasks.replay("bob",0).events().isEmpty());}
    }
}
