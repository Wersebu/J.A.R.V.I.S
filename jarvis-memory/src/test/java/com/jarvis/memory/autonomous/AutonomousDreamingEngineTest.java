package com.jarvis.memory.autonomous;
import com.jarvis.common.ai.ModelGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
class AutonomousDreamingEngineTest {
    @Test void optInAndIdleResourceGatesMustAllPass(){
        var pipeline=mock(AutonomousMemoryPipeline.class);var memory=mock(AutonomousMemoryService.class);
        @SuppressWarnings("unchecked") ObjectProvider<ModelGateway> gateways=mock(ObjectProvider.class);var gateway=mock(ModelGateway.class);when(gateways.iterator()).thenAnswer(i->List.of(gateway).iterator());
        new AutonomousDreamingEngine(pipeline,memory,gateways,false,1000).tick();verifyNoInteractions(pipeline,memory,gateways);
        when(pipeline.enabled()).thenReturn(true);when(pipeline.idle(1000)).thenReturn(true);when(gateway.snapshot()).thenReturn(Map.of("activeInferenceRequests",1));var engine=new AutonomousDreamingEngine(pipeline,memory,gateways,true,1000);engine.tick();verify(pipeline,never()).maintenance(any());
        when(gateway.snapshot()).thenReturn(Map.of("activeInferenceRequests",0));when(memory.owners()).thenReturn(Set.of("alice"));when(pipeline.maintenance(any())).thenAnswer(i->{((Runnable)i.getArgument(0)).run();return true;});engine.tick();verify(memory).consolidate("alice");
    }
}
