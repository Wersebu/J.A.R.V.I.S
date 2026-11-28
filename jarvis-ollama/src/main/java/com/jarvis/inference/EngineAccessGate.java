package com.jarvis.inference;
import com.jarvis.common.ai.AIProviderException;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
/** One JVM residency owner, shared with every coordinated Ollama call, including embeddings. */
@Component
public class EngineAccessGate {
    private final ReentrantReadWriteLock lock=new ReentrantReadWriteLock(true);
    private volatile String engine="ollama";
    public Lease enter(String requested) {
        try { if(!lock.readLock().tryLock(30,TimeUnit.SECONDS))throw new AIProviderException("ENGINE_SWITCHING"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AIProviderException("CANCELLED",e);}
        if(!engine.equals(requested)&&!requested.equals("cpu-auxiliary")){lock.readLock().unlock();throw new AIProviderException("ENGINE_INACTIVE: "+requested);}
        return lock.readLock()::unlock;
    }
    public Lease exclusive(){
        try {if(!lock.writeLock().tryLock(30,TimeUnit.SECONDS))throw new AIProviderException("ENGINE_BUSY: active generations did not drain");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AIProviderException("CANCELLED",e);}
        return lock.writeLock()::unlock;
    }
    void activate(String value){if(!lock.isWriteLockedByCurrentThread())throw new IllegalStateException();engine=value;}
    public interface Lease extends AutoCloseable {void close();}
}
