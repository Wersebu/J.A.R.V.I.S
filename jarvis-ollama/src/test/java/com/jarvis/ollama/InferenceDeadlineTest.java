package com.jarvis.ollama;
import com.jarvis.common.ai.InferenceSettingsContext;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InferenceDeadlineTest {
    @Test void deadlineClosesStalledBodyAndRestoresScope() throws Exception {
        var pool=Executors.newSingleThreadExecutor();var closed=new CountDownLatch(1);
        try{
            var result=pool.submit(()->{
                try(var settings=InferenceSettingsContext.open(new InferenceSettingsContext.Settings("test",50,true,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(100)));
                    var deadline=new InferenceDeadline()){
                    deadline.body(new InputStream(){public int read(){return -1;}public void close(){closed.countDown();}});
                    try{Thread.sleep(5000);fail("Deadline must interrupt owner");}catch(InterruptedException expected){}
                    assertThrows(OllamaException.class,deadline::check);
                }finally{Thread.interrupted();}
                assertNull(InferenceSettingsContext.current());return true;
            });
            assertTrue(result.get(2,TimeUnit.SECONDS));assertTrue(closed.await(1,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
    @Test void cancellationClosesBodyBeforeLongDeadline() throws Exception {
        var pool=Executors.newSingleThreadExecutor();var ready=new CountDownLatch(1);var closed=new CountDownLatch(1);
        try{
            var pending=pool.submit(()->{
                try(var settings=InferenceSettingsContext.open(new InferenceSettingsContext.Settings("test",50,true,System.nanoTime()+TimeUnit.MINUTES.toNanos(1)));
                    var deadline=new InferenceDeadline()){
                    deadline.body(new InputStream(){public int read(){return -1;}public void close(){closed.countDown();}});ready.countDown();
                    // Simulate a read that notices stream close but does not consume thread interruption.
                    while(closed.getCount()>0)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }finally{Thread.interrupted();}
            });
            assertTrue(ready.await(1,TimeUnit.SECONDS));pending.cancel(true);assertTrue(closed.await(1,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();}
    }
}
