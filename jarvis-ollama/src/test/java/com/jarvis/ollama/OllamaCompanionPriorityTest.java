package com.jarvis.ollama;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OllamaCompanionPriorityTest {
    private boolean waiting(Thread t){return t.getState()==Thread.State.WAITING || t.getState()==Thread.State.TIMED_WAITING;}
    @Test void interruptedWaiterDoesNotStarveBackground() throws Exception {
        var coordinator=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class));
        var active=coordinator.acquire(AIJobType.BACKGROUND,"active");
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread interactive=new Thread(()->{try{assertThrows(OllamaException.class,()->coordinator.acquire(AIJobType.CHAT,"cancelled"));}catch(Throwable ex){failure.set(ex);}});
        interactive.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!waiting(interactive) && System.nanoTime()<deadline)Thread.sleep(2);
        interactive.interrupt();interactive.join(2000);assertFalse(interactive.isAlive());assertNull(failure.get());active.close();
        var pool=Executors.newSingleThreadExecutor();
        try{pool.submit(()->{try(var ignored=coordinator.acquire(AIJobType.BACKGROUND,"later")){return true;}}).get(2,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
    }
    @Test void backgroundContextUsesExistingGateAndRestores() {
        var coordinator=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class));
        InferencePriorityContext.runBackground(()->{try(var permit=coordinator.acquire(AIJobType.CHAT,"task")){assertEquals(AIJobType.BACKGROUND,permit.jobType());}});
        try(var permit=coordinator.acquire(AIJobType.CHAT,"voice")){assertEquals(AIJobType.CHAT,permit.jobType());}
    }
    @Test void waitingConversationOvertakesQueuedBackground() throws Exception {
        var coordinator=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class));
        var active=coordinator.acquire(AIJobType.BACKGROUND,"active");
        var order=new java.util.concurrent.CopyOnWriteArrayList<String>();
        Thread background=new Thread(()->{try(var ignored=coordinator.acquire(AIJobType.BACKGROUND,"queued")){order.add("background");}});
        Thread conversation=new Thread(()->{try(var ignored=coordinator.acquire(AIJobType.CHAT,"conversation")){order.add("conversation");}});
        background.setDaemon(true);conversation.setDaemon(true);
        try {
            background.start();conversation.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while((!waiting(background) || !waiting(conversation)) && System.nanoTime()<deadline)Thread.sleep(2);
            assertTrue(waiting(conversation));
        } finally {active.close();}
        background.join(2000);conversation.join(2000);
        assertEquals(java.util.List.of("conversation","background"),order);
    }

}
