package com.jarvis.api.companion;
import com.jarvis.api.service.ChatService;
import com.jarvis.brain.BrainRouter;
import com.jarvis.common.ai.*;
import com.jarvis.common.dto.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompanionAnnouncementsTest {
    @Test void completionAnnouncementRequiresSuccessfulTerminalState() throws Exception {
        var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("mock");when(provider.chat(any(),anyString(),eq(AIJobType.BACKGROUND))).thenReturn(new ChatResponse("A verified status."));
        var router=mock(BrainRouter.class);when(router.select(any(com.jarvis.common.dto.ChatRequest.class))).thenReturn(new Brain(BrainType.CLASSIFIER,"mock","test-model","Test"));
        var chat=mock(ChatService.class);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(chat.chat(any())).thenAnswer(i->{entered.countDown();release.await();return new ChatResponse("done");});
        var announcements=new TaskAnnouncements(List.of(provider),router,true,"Concise test personality");
        try(var tasks=new CompanionTasks(chat,1)) {
            org.springframework.test.util.ReflectionTestUtils.setField(tasks,"announcements",announcements);
            var task=tasks.submit("alice",new CompanionTasks.Request("work","TASK",50,10,1));assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertFalse(tasks.replay("alice",0).events().stream().anyMatch(e->e.type().equals("assistant.task.announcement") && ((Map<?,?>)e.payload()).get("kind").equals("COMPLETED")));
            release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);boolean found=false;
            while(System.nanoTime()<deadline){found=tasks.replay("alice",0).events().stream().anyMatch(e->e.type().equals("assistant.task.announcement")&&((Map<?,?>)e.payload()).get("kind").equals("COMPLETED"));if(found)break;Thread.sleep(5);}
            assertTrue(found);assertEquals("COMPLETED",tasks.get("alice",task.taskId()).state());assertTrue(tasks.replay("bob",0).events().isEmpty());
        }finally{release.countDown();announcements.close();}
    }
}
