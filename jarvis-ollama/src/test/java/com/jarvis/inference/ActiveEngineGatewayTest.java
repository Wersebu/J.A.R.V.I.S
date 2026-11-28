package com.jarvis.inference;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.ollama.OllamaRequestCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ActiveEngineGatewayTest {
 @Test void threeRuntimeSlotsAdmitThreeRequestsWithinTotalContextBudget()throws Exception{
  var control=mock(InferenceControl.class,CALLS_REAL_METHODS);when(control.selection()).thenReturn(new InferenceControl.Selection("c","llamacpp","m.gguf",Map.of("contextSize",24576,"parallelSlots",3)));
  ObjectProvider<InferenceControl> provider=mock(ObjectProvider.class);when(provider.getIfAvailable()).thenReturn(control);
  var gateway=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),1,0,10,0,5000);gateway.engines(provider);gateway.contextBudget(24576,24576);
  var gate=new EngineAccessGate();try(var exclusive=gate.exclusive()){gate.activate("llamacpp");}gateway.engineGate(gate);
  try(var one=gateway.acquire(AIJobType.CHAT,"one");var two=gateway.acquire(AIJobType.CHAT,"two");var three=gateway.acquire(AIJobType.CHAT,"three")){
   assertThat(gateway.snapshot()).containsEntry("configuredConcurrency",3).containsEntry("activeInferenceRequests",3);
   try(var executor=Executors.newSingleThreadExecutor()){
    var waiting=executor.submit(()->{try(var lease=gateway.acquire(AIJobType.CHAT,"four")){return true;}});
    assertThatThrownBy(()->waiting.get(100,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
    one.close();assertThat(waiting.get(2,TimeUnit.SECONDS)).isTrue();
   }
  }
  assertThat(gateway.snapshot()).containsEntry("activeInferenceRequests",0);
 }
}
