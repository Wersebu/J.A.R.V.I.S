package com.jarvis.api.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.*;
import com.jarvis.common.dto.*;
import com.jarvis.common.memory.*;
import com.jarvis.common.run.ConversationTurns;
import com.jarvis.memory.*;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.image.*;
import com.jarvis.memory.sqlite.*;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import com.jarvis.tools.taskplan.DynamicPlanService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real SQLite/task/synchronization integration. Inference is an explicitly controlled test double. */
class TaskAutonomyIntegrationTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private SQLiteConversationMemoryService memory;
    private SQLiteConversationMessageRepository messages;
    private SQLiteConversationRepository conversations;
    private TaskResultDelivery delivery;
    @BeforeEach void database(){
        var properties=new MemoryProperties(temp.resolve("memory.db").toString(),20,null,null,null,null);
        var factory=new SQLiteConnectionFactory(properties);new SQLiteMemoryInitializer(factory).afterPropertiesSet();
        messages=new SQLiteConversationMessageRepository(factory);conversations=new SQLiteConversationRepository(factory);
        memory=new SQLiteConversationMemoryService(new SQLiteWorkingMemoryStore(factory,properties),conversations,messages,
            new SQLiteConversationImageRegistry(factory,new ConversationImageProperties(true,null,0,0,null)));
        delivery=new TaskResultDelivery(memory,conversations);
        CurrentUserContext.runAs("alice",()->conversations.createIfAbsent("source",Instant.now()));
    }
    private CompanionTasks engine(ChatService chat,CompanionDocumentStore store){
        var tasks=new CompanionTasks(chat,3);tasks.configureHistory(store,mapper,new DynamicPlanService(store,mapper),30);
        ReflectionTestUtils.setField(tasks,"resultDelivery",delivery);return tasks;
    }
    private List<ConversationMessage> history(String owner,String conversation){
        var result=new ArrayList<ConversationMessage>();CurrentUserContext.runAs(owner,()->result.addAll(messages.getAllMessages(conversation)));return result;
    }
    private void until(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(15);assertTrue(condition.getAsBoolean());}
    @Test void threeIndependentAgentsDeliverPartialAndAggregateWithoutClientsOrUserMessages()throws Exception{
        var chat=mock(ChatService.class);var entered=new CountDownLatch(3);var release=new CountDownLatch(1);var finishOthers=new CountDownLatch(1);
        Set<String> contexts=ConcurrentHashMap.newKeySet();
        when(chat.chat(any())).thenAnswer(i->{ChatRequest request=i.getArgument(0);assertEquals("alice",CurrentUserContext.requiredUserId());contexts.add(request.conversationId());entered.countDown();release.await();if(!request.message().equals("Pierwszy materiał"))finishOthers.await();return new ChatResponse("Pełny materiał: "+request.message()+"\nPrzykład i objaśnienie.");});
        try(var store=new SQLiteCompanionDocumentStore(true,temp.resolve("tasks.db").toString());var tasks=engine(chat,store)){
            var adapter=new CompanionTaskSubmissionAdapter(tasks);
            try(var original=ConversationTurns.reserve("alice","source")){
                for(String name:List.of("Pierwszy materiał","Drugi materiał","Trzeci materiał"))adapter.submit("alice","source",name,"TASK","request-1");
                assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(3,contexts.size());release.countDown();
                until(()->tasks.list("alice").stream().anyMatch(t->t.state().equals("COMPLETED")));
                tasks.processCompletions();assertTrue(history("alice","source").isEmpty());
            }
            until(()->history("alice","source").size()==1);
            assertTrue(history("alice","source").getFirst().content().contains("Pełny materiał: Pierwszy"));
            // Ordinary conversation can acquire the source turn while two agents are still active.
            // Foreground ingress reserves FIFO even if the delivery writer is just releasing its turn.
            try(var turn=ConversationTurns.reserve("alice","source")){turn.await();memoryAsUser();}
            finishOthers.countDown();until(()->history("alice","source").size()==5);
            var all=history("alice","source");assertEquals(4,all.stream().filter(m->m.role()==MessageRole.ASSISTANT).count());
            assertTrue(all.getLast().content().contains("Pełny materiał: Trzeci materiał"));
            assertTrue(all.getLast().content().contains("Pełny materiał: Drugi materiał"));
            tasks.processCompletions();tasks.processCompletions();assertEquals(5,history("alice","source").size());
            assertTrue(history("bob","source").isEmpty());assertTrue(tasks.replay("bob",0).events().isEmpty());
            assertEquals(4,tasks.replay("alice",0).events().stream().filter(e->e.type().equals("assistant.message.created")).count());
            assertEquals(4,((List<?>)tasks.state("alice").get("deliveries")).size());
        }finally{release.countDown();finishOthers.countDown();}
    }
    private void memoryAsUser(){CurrentUserContext.runAs("alice",()->memory.addMessage("source",ConversationMessage.chat("source","interactive",MessageRole.USER,"Co tam?",Instant.now())));}
    @Test void restartReprocessesDurableResultAndCrashWindowCannotDuplicateMessage()throws Exception{
        Path path=temp.resolve("restart.db");var chat=mock(ChatService.class);when(chat.chat(any())).thenReturn(new ChatResponse("Zachowany pełny poradnik."));
        String taskId;
        try(var store=new SQLiteCompanionDocumentStore(true,path.toString());var tasks=engine(chat,store)){
            var t=tasks.submit("alice",new CompanionTasks.Request("Poradnik","TASK",50,30,1),"source","request");taskId=t.taskId();
            until(()->history("alice","source").size()==1);
            // Crash after message commit but before outbox acknowledgement.
            store.delete("alice","task-delivery","task-result/"+taskId);
        }
        var noExecution=mock(ChatService.class);
        try(var store=new SQLiteCompanionDocumentStore(true,path.toString());var tasks=engine(noExecution,store)){
            until(()->store.find("alice","task-delivery","task-result/"+taskId).isPresent());
            assertEquals(1,history("alice","source").size());verifyNoInteractions(noExecution);
            assertEquals("Zachowany pełny poradnik.",tasks.get("alice",taskId).result());
        }
    }
    @Test void emptyAndIncompleteResultsFailWithoutStoppingOtherAgents()throws Exception{
        var chat=mock(ChatService.class);when(chat.chat(any())).thenAnswer(i->{String text=((ChatRequest)i.getArgument(0)).message();if(text.equals("empty"))return new ChatResponse(" ");if(text.equals("partial"))throw new IncompleteTaskException("OUTPUT_LIMIT","Zachowany fragment");return new ChatResponse("Gotowy wynik");});
        try(var tasks=engine(chat,new InMemoryCompanionStore())){
            for(String name:List.of("empty","partial","success"))tasks.submit("alice",new CompanionTasks.Request(name,"TASK",50,30,1),"source","one");
            until(()->tasks.list("alice").stream().allMatch(t->Set.of("COMPLETED","FAILED","PARTIALLY_COMPLETED").contains(t.state())));
            assertEquals(1,tasks.list("alice").stream().filter(t->t.state().equals("COMPLETED")).count());
            assertTrue(tasks.list("alice").stream().anyMatch(t->"Zachowany fragment".equals(t.result())&&t.state().equals("PARTIALLY_COMPLETED")));
            until(()->history("alice","source").size()==4);
        }
    }
    @Test void busyConversationAndDeletedConversationAreNotOverwritten(){
        try(var turn=ConversationTurns.reserve("alice","source")){
            assertEquals(TaskResultDelivery.Status.BUSY,delivery.deliver("alice","source","id","wynik",Instant.now()));
        }
        CurrentUserContext.runAs("alice",()->memory.deleteConversation("source"));
        assertEquals(TaskResultDelivery.Status.DELETED,delivery.deliver("alice","source","id","wynik",Instant.now()));
        assertTrue(history("alice","source").isEmpty());
    }
    @Test void dependenciesWaitForActualResultsAndFailedDependencyDoesNotExecuteChild()throws Exception{
        var chat=mock(ChatService.class);var release=new CountDownLatch(1);var seen=new CopyOnWriteArrayList<String>();
        when(chat.chat(any())).thenAnswer(i->{String text=((ChatRequest)i.getArgument(0)).message();seen.add(text);if(text.equals("parent"))release.await();return new ChatResponse("Wynik zależności");});
        try(var tasks=engine(chat,new InMemoryCompanionStore())){
            var parent=tasks.submit("alice",new CompanionTasks.Request("parent","TASK",50,30,1),"source","dependency");
            var child=tasks.submit("alice",new CompanionTasks.Request("child","TASK",50,30,1,"Etap zależny",List.of(parent.taskId()),null,null),"source","dependency");
            assertEquals("WAITING",child.state());assertTrue(seen.stream().noneMatch(s->s.startsWith("child")));
            release.countDown();until(()->tasks.get("alice",child.taskId()).state().equals("COMPLETED"));
            assertTrue(seen.stream().anyMatch(s->s.startsWith("child")&&s.contains("Wynik zależności")));
            var cancelled=tasks.submit("alice",new CompanionTasks.Request("blocked","TASK",50,30,1,null,List.of(parent.taskId()),null,null),"source","cancel");
            tasks.cancel("alice",cancelled.taskId());
            var never=tasks.submit("alice",new CompanionTasks.Request("never-run","TASK",50,30,1,null,List.of(cancelled.taskId()),null,null),"source","cancel");
            until(()->tasks.get("alice",never.taskId()).state().equals("FAILED"));assertTrue(seen.stream().noneMatch(s->s.startsWith("never-run")));
            assertThrows(Exception.class,()->tasks.submit("bob",new CompanionTasks.Request("leak","TASK",50,30,1,null,List.of(parent.taskId()),null,null),"source","other"));
        }finally{release.countDown();}
    }
    @Test void restartResumesOnlyNeverStartedWork()throws Exception{
        try(var store=new SQLiteCompanionDocumentStore(true,temp.resolve("queued.db").toString())){
            var now=Instant.now();String id=UUID.randomUUID().toString();var request=new CompanionTasks.Request("Zachowane polecenie","TASK",50,30,1);
            var task=new CompanionTasks.Task(id,"TASK","QUEUED",50,null,now,now,null,null,"source","companion-"+id);
            store.putAll(List.of(new CompanionDocumentStore.Document("alice","task",id,mapper.writeValueAsString(task),now),new CompanionDocumentStore.Document("alice","task-input",id,mapper.writeValueAsString(new CompanionTasks.Submission(request,"Odzyskane zadanie","group",now.plusSeconds(30))),now)));
            var chat=mock(ChatService.class);when(chat.chat(any())).thenReturn(new ChatResponse("Wznowiony materiał"));
            try(var tasks=engine(chat,store)){until(()->tasks.get("alice",id).state().equals("COMPLETED"));until(()->history("alice","source").size()==1);verify(chat,times(1)).chat(any());}
        }
    }
    @Test void concurrentTasksMaintainIndependentVerifiedPlans()throws Exception{
        var chat=mock(ChatService.class);var store=new InMemoryCompanionStore();var entered=new CountDownLatch(3);var release=new CountDownLatch(1);
        try(var tasks=engine(chat,store)){
            var plans=(DynamicPlanService)ReflectionTestUtils.getField(tasks,"plans");
            when(chat.chat(any())).thenAnswer(i->{String id=TaskExecutionContext.current();var plan=plans.create("alice",id,"Przygotowanie materiału",List.of("Zapis materiału"));var step=plan.steps().getFirst();plan=plans.update("alice",id,plan.revision(),step.stepId(),"IN_PROGRESS",null,null,null);entered.countDown();release.await();
                java.nio.file.Files.writeString(temp.resolve(id+".txt"),"Rzeczywisty materiał testowy");
                var evidence=plans.recordEvidence("alice",id,"test-file-write","WRITE",true);plan=plans.update("alice",id,plan.revision(),step.stepId(),"COMPLETED","Zapisano plik",null,evidence.evidenceRef());plans.complete("alice",id,plan.revision());return new ChatResponse("Rzeczywisty materiał testowy");});
            var ids=new ArrayList<String>();for(int i=0;i<3;i++)ids.add(tasks.submit("alice",new CompanionTasks.Request("Materiał "+i,"TASK",50,30,1)).taskId());
            assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals(3,ids.stream().map(id->tasks.plan("alice",id).planId()).distinct().count());release.countDown();
            until(()->tasks.list("alice").stream().allMatch(t->t.state().equals("COMPLETED")));
            for(String id:ids){assertEquals("COMPLETED",tasks.plan("alice",id).status());assertTrue(tasks.planRevisions("alice",id).size()>=4);assertTrue(java.nio.file.Files.exists(temp.resolve(id+".txt")));}
        }finally{release.countDown();}
    }
    @Test void identicalRequestKeysInDifferentConversationsDoNotMergeTasksOrResults()throws Exception{
        CurrentUserContext.runAs("alice",()->conversations.createIfAbsent("other",Instant.now()));
        var chat=mock(ChatService.class);when(chat.chat(any())).thenAnswer(i->new ChatResponse("Wynik kontekstu "+((ChatRequest)i.getArgument(0)).conversationId()));
        try(var tasks=engine(chat,new InMemoryCompanionStore())){
            var first=tasks.submit("alice",new CompanionTasks.Request("same goal","TASK",50,30,1),"source","same-key");
            var second=tasks.submit("alice",new CompanionTasks.Request("same goal","TASK",50,30,1),"other","same-key");
            assertNotEquals(first.taskId(),second.taskId());
            until(()->history("alice","source").size()==1&&history("alice","other").size()==1);
            assertTrue(history("alice","source").getFirst().content().contains(first.executionConversationId()));
            assertFalse(history("alice","source").getFirst().content().contains(second.executionConversationId()));
            assertTrue(history("alice","other").getFirst().content().contains(second.executionConversationId()));
        }
    }
    @Test void delegatedEnglishGoalRetainsPolishSourceLanguageReference()throws Exception{
        CurrentUserContext.runAs("alice",()->memory.addMessage("source",ConversationMessage.chat("source","original",MessageRole.USER,"Przygotuj materiały po polsku.",Instant.now())));
        var chat=mock(ChatService.class);when(chat.chat(any())).thenAnswer(i->{assertEquals("Przygotuj materiały po polsku.",TaskExecutionContext.languageReference());return new ChatResponse("Gotowy materiał po polsku.");});
        try(var tasks=engine(chat,new InMemoryCompanionStore())){
            var task=tasks.submit("alice",new CompanionTasks.Request("Prepare a document","TASK",50,30,1),"source","request");
            until(()->tasks.get("alice",task.taskId()).state().equals("COMPLETED"));until(()->history("alice","source").size()==2);
        }
    }
}
