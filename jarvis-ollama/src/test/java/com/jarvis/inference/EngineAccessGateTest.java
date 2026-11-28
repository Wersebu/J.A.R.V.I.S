package com.jarvis.inference;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
class EngineAccessGateTest {
 @Test void refusesInactiveEngine(){var gate=new EngineAccessGate();assertThatThrownBy(()->gate.enter("llamacpp")).hasMessageContaining("ENGINE_INACTIVE");try(var exclusive=gate.exclusive()){gate.activate("llamacpp");}assertThatThrownBy(()->gate.enter("ollama")).hasMessageContaining("ENGINE_INACTIVE");try(var read=gate.enter("llamacpp")){} }
 @Test void switchWaitsUntilRequestFinishes()throws Exception{var gate=new EngineAccessGate();var lease=gate.enter("ollama");try(var pool=Executors.newSingleThreadExecutor()){var attempted=new CountDownLatch(1);var switched=pool.submit(()->{attempted.countDown();try(var exclusive=gate.exclusive()){gate.activate("llamacpp");}return true;});assertThat(attempted.await(1,TimeUnit.SECONDS)).isTrue();assertThat(switched.isDone()).isFalse();lease.close();assertThat(switched.get(2,TimeUnit.SECONDS)).isTrue();}}
 @Test void interruptedQueueDoesNotLeakLock()throws Exception{var gate=new EngineAccessGate();Thread.currentThread().interrupt();try{assertThatThrownBy(()->gate.enter("ollama")).hasMessageContaining("CANCELLED");}finally{Thread.interrupted();}try(var lease=gate.exclusive()){} }
}
