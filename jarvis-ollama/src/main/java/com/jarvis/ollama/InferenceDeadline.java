package com.jarvis.ollama;

import com.jarvis.common.ai.InferenceSettingsContext;
import java.io.InputStream;
import java.util.concurrent.*;

/** Cancels header wait and stalled streaming bodies. Admission is released only by the owner. */
public final class InferenceDeadline implements AutoCloseable {
    private static final ThreadLocal<InferenceDeadline> CURRENT=new ThreadLocal<>();
    private final InferenceDeadline previous=CURRENT.get();
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"inference-deadline");t.setDaemon(true);return t;});
    private final Thread owner=Thread.currentThread();
    private volatile InputStream body;
    private volatile boolean expired;
    private boolean closed;
    private final ScheduledFuture<?> alarm;
    private final ScheduledFuture<?> cancellationMonitor;
    public InferenceDeadline() {
        CURRENT.set(this);
        var settings=InferenceSettingsContext.current();
        alarm=settings==null?null:TIMER.schedule(this::expire,Math.max(0,settings.deadlineNanos()-System.nanoTime()),TimeUnit.NANOSECONDS);
        cancellationMonitor=TIMER.scheduleWithFixedDelay(()->{if(owner.isInterrupted())expire();},100,100,TimeUnit.MILLISECONDS);
    }
    private synchronized void expire() {
        if(closed||expired)return;
        expired=true;owner.interrupt();
        if(body!=null){InputStream stream=body;Thread.startVirtualThread(()->{try{stream.close();}catch(Exception ignored){}});}
    }
    public static void attach(InputStream stream){var current=CURRENT.get();if(current!=null)current.body(stream);}
    synchronized InputStream body(InputStream stream) { body=stream;if(expired){try{stream.close();}catch(Exception ignored){}check();}return stream; }
    void check() { if(expired)throw new OllamaException("Inference execution deadline exceeded");if(owner.isInterrupted())throw new OllamaException("Inference cancelled"); }
    public synchronized void close(){closed=true;if(alarm!=null)alarm.cancel(false);cancellationMonitor.cancel(false);if(body!=null)try{body.close();}catch(Exception ignored){}if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
