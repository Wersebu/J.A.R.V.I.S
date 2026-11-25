package com.jarvis.api.companion;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.companion.*;
import com.jarvis.common.dto.*;
import com.jarvis.common.auth.CurrentUserContext;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Phase2DelegationTest {
    @Test void actualDelegationToolReturnsReceiptWhileWorkerIsStillBusy() throws Exception {
        var chat=mock(ChatService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(i->{entered.countDown();release.await();return new ChatResponse("result");});
        try(var tasks=new CompanionTasks(chat,1)){
            var adapter=new CompanionTaskSubmissionAdapter(tasks);
            var factory=new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("submission",adapter));
            var tool=new com.jarvis.tools.runtime.CompanionDelegationTool(factory.getBeanProvider(CompanionTaskSubmission.class));
            var receipt=new java.util.concurrent.atomic.AtomicReference<com.jarvis.tools.ToolResult>();
            CurrentUserContext.runAs("alice",()->receipt.set(tool.execute(new com.jarvis.tools.ToolRequest("companion","SUBMIT","source","request-id","","",Map.of("message","Research independently")))));
            assertTrue(receipt.get().success(),receipt.get().errorMessage());assertNotNull(receipt.get().data().get("taskId"));assertTrue(entered.await(1,TimeUnit.SECONDS));
            assertEquals("RUNNING",tasks.get("alice",receipt.get().data().get("taskId").toString()).state());
            assertEquals(1,tasks.list("alice").size());
        }finally{release.countDown();}
    }
    @Test void independentTasksReturnImmediatelyReceiveInstructionsAndLeaveConversationAvailable() throws Exception {
        var chat=mock(ChatService.class);var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        var contexts=new ConcurrentHashMap<String,String>();var instructions=new CopyOnWriteArrayList<String>();
        when(chat.chat(any())).thenAnswer(call->{
            ChatRequest r=call.getArgument(0);
            if(r.message().equals("ordinary conversation"))return new ChatResponse("available");
            assertEquals("alice",CurrentUserContext.requireAuthenticatedUserId());
            contexts.put(TaskExecutionContext.current(),r.conversationId());entered.countDown();release.await();
            instructions.addAll(TaskInstructionContext.drain());return new ChatResponse("verified result");
        });
        try(var tasks=new CompanionTasks(chat,2)){
            var adapter=new CompanionTaskSubmissionAdapter(tasks);
            var first=adapter.submit("alice","source","work one","RESEARCH","call1");
            var again=adapter.submit("alice","source","work one","RESEARCH","call1");assertEquals(first.get("taskId"),again.get("taskId"));
            var second=adapter.submit("alice","source","work two","TASK","call2");
            assertNotEquals(first.get("taskId"),second.get("taskId"));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("available",chat.chat(new ChatRequest("source","ordinary conversation")).response());
            adapter.instruct("alice",first.get("taskId").toString(),"Focus on the verified sources");
            assertEquals(2L,tasks.metrics("alice").get("activeTasks"));assertEquals(0L,tasks.metrics("bob").get("activeTasks"));
            assertThrows(Exception.class,()->adapter.get("bob",first.get("taskId").toString()));
            release.countDown();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(tasks.list("alice").stream().anyMatch(t->!t.state().equals("COMPLETED"))&&System.nanoTime()<deadline)Thread.sleep(5);
            assertEquals(2,contexts.size());assertEquals(2,new HashSet<>(contexts.values()).size());assertTrue(instructions.contains("Focus on the verified sources"));
            assertEquals("source",tasks.get("alice",first.get("taskId").toString()).conversationId());
            assertEquals("verified result",adapter.get("alice",first.get("taskId").toString()).get("result"));
            assertEquals(2,tasks.replay("alice",0).events().stream().filter(e->e.type().equals("task.completed")).count());
        }finally{release.countDown();}
    }
    @Test void sameChatRequestDeduplicatesRetriesButKeepsThreeIndependentGoals() {
        var chat = mock(ChatService.class);
        when(chat.chat(any())).thenReturn(new ChatResponse("done"));
        try (var tasks = new CompanionTasks(chat, 3)) {
            var adapter = new CompanionTaskSubmissionAdapter(tasks);
            var ids = new HashSet<Object>();
            for (String goal : List.of("alpha", "beta", "gamma")) {
                var first = adapter.submit("alice", "source", goal, "TASK", "same-chat-request");
                var retry = adapter.submit("alice", "source", goal, "TASK", "same-chat-request");
                assertEquals(first.get("taskId"), retry.get("taskId"));
                ids.add(first.get("taskId"));
            }
            assertEquals(3, ids.size()); assertEquals(3, tasks.list("alice").size());
        }
    }
    @Test void templatesNeverInvokeModelAndActivityScopesAreTruthful(){
        var provider=mock(com.jarvis.common.ai.AIProvider.class);var router=mock(com.jarvis.brain.BrainRouter.class);
        var announcements=new TaskAnnouncements(List.of(provider),router,true,"ignored");
        var task=new CompanionTasks.Task("id","TASK","QUEUED",50,null,java.time.Instant.now(),java.time.Instant.now(),null,null);
        var texts=new ArrayList<String>();announcements.generate("alice",task,"ACKNOWLEDGMENT",texts::add);
        announcements.generate("alice",task,"COMPLETED",texts::add);assertEquals(1,texts.size());verifyNoInteractions(provider,router);
        var tracker=new ActivityTracker();CurrentUserContext.runAs("alice",()->{
            try(var tool=tracker.start("TOOL","r","search");var helper=tracker.start("AGENT","r-agent","agent__run");var coding=tracker.start("CODING_TASK","r-code","coding-task","code-task")){
                assertEquals(1L,tracker.snapshot("alice").get("activeToolExecutions"));assertEquals(1L,tracker.snapshot("alice").get("activeSynchronousAgents"));assertEquals(0L,tracker.snapshot("bob").get("activeToolExecutions"));
                assertEquals(1L,tracker.snapshot("alice").get("activeCodingTasks"));
            }
        });assertEquals(0L,tracker.snapshot("alice").get("activeToolExecutions"));
    }
}
