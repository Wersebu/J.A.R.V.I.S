package com.jarvis.api.companion;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.auth.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompanionTasksTest {
    private CompanionTasks.Request request(String text,int priority){return new CompanionTasks.Request(text,"TASK",priority,10,1);}
    private CompanionTasks.Task await(CompanionTasks tasks,String id,String state) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime()<deadline){var task=tasks.get("alice",id);if(task.state().equals(state))return task;Thread.sleep(5);}
        fail("Expected "+state+" but got "+tasks.get("alice",id).state());return null;
    }
    @Test void ownerIsolationContextAndReplay() throws Exception {
        ChatService chat=mock(ChatService.class);
        when(chat.chat(any())).thenAnswer(i->{assertEquals("alice",CurrentUserContext.requireAuthenticatedUserId());return new ChatResponse("done");});
        try(var tasks=new CompanionTasks(chat,1)){
            var task=tasks.submit("alice",request("hello",50));
            assertThrows(ResponseStatusException.class,()->tasks.get("bob",task.taskId()));
            assertThrows(ResponseStatusException.class,()->tasks.cancel("bob",task.taskId()));
            assertTrue(tasks.list("bob").isEmpty());assertTrue(tasks.replay("bob",0).events().isEmpty());
            assertEquals("done",await(tasks,task.taskId(),"COMPLETED").result());
            var events=tasks.replay("alice",0).events();assertEquals(3,events.size());
            assertEquals("task.created",events.get(0).type());assertEquals("task.completed",events.get(2).type());
            assertEquals("COMPLETED",tasks.cancel("alice",task.taskId()).state());
            assertTrue(tasks.replay("alice",999).resetRequired());
        }
    }
    @Test void priorityQueueAndQueuedCancellation() throws Exception {
        ChatService chat=mock(ChatService.class);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var seen=new CopyOnWriteArrayList<String>();
        when(chat.chat(any())).thenAnswer(i->{String msg=((com.jarvis.common.dto.ChatRequest)i.getArgument(0)).message();seen.add(msg);if(msg.equals("block")){entered.countDown();release.await();}return new ChatResponse(msg);});
        try(var tasks=new CompanionTasks(chat,1)){
            tasks.submit("alice",request("block",50));assertTrue(entered.await(2,TimeUnit.SECONDS));
            var low=tasks.submit("alice",request("low",90));var high=tasks.submit("alice",request("high",10));var cancel=tasks.submit("alice",request("cancel",5));
            assertEquals("CANCELLED",tasks.cancel("alice",cancel.taskId()).state());release.countDown();
            await(tasks,low.taskId(),"COMPLETED");await(tasks,high.taskId(),"COMPLETED");assertEquals(java.util.List.of("block","high","low"),seen);
        }finally{release.countDown();}
    }
    @Test void runningCancellationAndTimeoutWaitForWorkerExit() throws Exception {
        ChatService chat=mock(ChatService.class);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(i->{entered.countDown();while(release.getCount()>0){try{release.await();}catch(InterruptedException ignored){}}return new ChatResponse("late");});
        try(var tasks=new CompanionTasks(chat,1)){
            var task=tasks.submit("alice",new CompanionTasks.Request("slow","TASK",50,1,1));assertTrue(entered.await(2,TimeUnit.SECONDS));
            await(tasks,task.taskId(),"CANCEL_REQUESTED");assertNull(tasks.get("alice",task.taskId()).result());
            release.countDown();await(tasks,task.taskId(),"TIMED_OUT");
        }finally{release.countDown();}
    }
    @Test void rejectsUnsafeRetriesAndInvalidRequests(){try(var tasks=new CompanionTasks(mock(ChatService.class),1)){
        assertThrows(ResponseStatusException.class,()->tasks.submit("alice",new CompanionTasks.Request("x","TASK",1,5,2)));
        assertThrows(ResponseStatusException.class,()->tasks.submit("alice",request("",50)));
        assertThrows(ResponseStatusException.class,()->tasks.submit("alice",request("x",101)));
    }}
    @Test void boundedAdmissionAndReplayGap() throws Exception {
        ChatService chat=mock(ChatService.class);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(i->{entered.countDown();release.await();return new ChatResponse("done");});
        try(var tasks=new CompanionTasks(chat,1)) {
            tasks.submit("alice",request("block",50));assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=1;i<128;i++)tasks.submit("alice",request("queued",50));
            var rejection=assertThrows(ResponseStatusException.class,()->tasks.submit("alice",request("overflow",50)));
            assertEquals(429,rejection.getStatusCode().value());
            for(var task:tasks.list("alice"))tasks.cancel("alice",task.taskId());
        } finally { release.countDown(); }
        when(chat.chat(any())).thenReturn(new ChatResponse("done"));
        try(var tasks=new CompanionTasks(chat,1)) {
            for(int i=0;i<180;i++){var task=tasks.submit("alice",request("quick",50));await(tasks,task.taskId(),"COMPLETED");}
            assertTrue(tasks.replay("alice",0).resetRequired());
            long latest=tasks.replay("alice",Long.MAX_VALUE).latestSequence();
            assertEquals(1,tasks.replay("alice",latest-1).events().size());
        }
    }

    @Test void runningCancelIsConfirmedOnlyAfterExit() throws Exception {
        ChatService chat=mock(ChatService.class);CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(i->{entered.countDown();try{release.await();}catch(InterruptedException ex){interrupted.countDown();release.await();}return new ChatResponse("late");});
        try(var tasks=new CompanionTasks(chat,1)) {
            var task=tasks.submit("alice",request("slow",50));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED",tasks.cancel("alice",task.taskId()).state());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            assertEquals("CANCEL_REQUESTED",tasks.get("alice",task.taskId()).state());
            release.countDown();assertNull(await(tasks,task.taskId(),"CANCELLED").result());
        }finally{release.countDown();}
    }

}
