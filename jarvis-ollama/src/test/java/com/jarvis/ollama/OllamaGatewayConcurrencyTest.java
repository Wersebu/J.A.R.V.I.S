package com.jarvis.ollama;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.event.CognitiveEventBus;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class OllamaGatewayConcurrencyTest {
    @Test void twoRequestsHoldPermitsConcurrentlyAndReservationProtectsConversation() throws Exception {
        var gateway=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),2,1,8,0,100);
        try(var background=gateway.acquire(AIJobType.BACKGROUND,"one")) {
            try(var voice=gateway.acquire(AIJobType.CHAT,"two")) {assertEquals(2,gateway.snapshot().get("activeInferenceRequests"));}
            assertThrows(OllamaException.class,()->gateway.acquire(AIJobType.BACKGROUND,"reserved"));
        }
        assertEquals(0,gateway.snapshot().get("activeInferenceRequests"));
    }
    @Test void threeAndDoubleCloseAreSafe() {
        var gateway=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),3,0,8,0,100);
        var a=gateway.acquire(AIJobType.BACKGROUND,"a");var b=gateway.acquire(AIJobType.BACKGROUND,"b");var c=gateway.acquire(AIJobType.BACKGROUND,"c");
        assertEquals(3,gateway.snapshot().get("activeInferenceRequests"));a.close();a.close();assertEquals(2,gateway.snapshot().get("activeInferenceRequests"));b.close();c.close();
    }
    @Test void heapPressureAndInvalidConfigurationFailClosed(){
        assertThrows(IllegalArgumentException.class,()->new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),2,2,8,0,100));
        var gateway=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),2,1,8,Long.MAX_VALUE,25);
        assertThrows(OllamaException.class,()->gateway.acquire(AIJobType.BACKGROUND,"pressure"));
        assertEquals(0,gateway.snapshot().get("waitingRequests"));
    }
}
