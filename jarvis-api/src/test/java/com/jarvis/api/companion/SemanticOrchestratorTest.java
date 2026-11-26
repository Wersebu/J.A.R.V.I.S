package com.jarvis.api.companion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.companion.InMemoryCompanionStore;
import com.jarvis.common.companion.AutonomousOrchestration;
import com.jarvis.tools.runtime.*;
import com.jarvis.tools.schema.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticOrchestratorTest {
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private final AIProvider provider=mock(AIProvider.class);
    private final CompanionTasks tasks=mock(CompanionTasks.class);
    private final InMemoryCompanionStore store=new InMemoryCompanionStore();
    private final Brain brain=new Brain(BrainType.FAST,"fake","fake-model","");
    private SemanticOrchestrator engine(){
        when(provider.provider()).thenReturn("fake");
        var schemas=mock(NativeToolSchemaMapper.class);
        when(schemas.definitions(ToolIntent.NO_TOOL)).thenReturn(List.of(new NativeToolDefinition("fx__quote","Read exchange rate",Map.of())));
        var registry=mock(ToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of(new ToolDefinition("fx","Exchange rates",List.of(new ToolOperationDefinition("quote","Read",List.of(),false,ToolSafetyLevel.READ)))));
        return new SemanticOrchestrator(List.of(provider),schemas,registry,tasks,store,mapper,true);
    }
    private ModelResponse response(String json){return new ModelResponse(json,"",List.of(),"stop",new ModelUsage(10,5,15));}
    private String plan(String tools,String dependencies){return "{\"action\":\"DELEGATE\",\"answer\":\"\",\"tools\":[],\"agents\":[{\"kind\":\"RETRIEVAL\",\"key\":\"a\",\"name\":\"Kurs dolara\",\"goal\":\"Read USD-PLN\",\"tools\":"+tools+",\"dependsOn\":"+dependencies+"}]}";}

    @Test void greetingUsesModelDirectDecisionWithoutCreatingWorkers(){
        var engine=engine();when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response("{\"action\":\"DIRECT\",\"answer\":\"Cześć!\",\"tools\":[],\"agents\":[]}"));
        assertEquals("DIRECT",engine.orchestrate("owner","conversation","request","Cześć",brain).action());
        verify(tasks,never()).submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet());
        engine.orchestrate("owner","conversation","request","Cześć",brain);
        verify(provider,times(1)).toolChat(any(),anyList(),anyList(),any());
    }

    @Test void unavailableScopeNeverBecomesAFalseSuccessfulRetrievalWorker(){
        var engine=engine();when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response("{\"action\":\"DELEGATE\",\"answer\":\"\",\"families\":[\"fx\"]}"),response(plan("[]","[]")));
        assertThrows(IllegalStateException.class,()->engine.orchestrate("owner","conversation","request","Read current exchange rate",brain));
        verify(provider,times(4)).toolChat(any(),anyList(),anyList(),any());
        verify(tasks,never()).submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet());
        assertEquals(1,store.documents("owner","semantic-route-v2","").size());
        assertTrue(store.documents("owner","semantic-plan-v2","").isEmpty());
    }

    @Test void cyclicOrUnknownDependenciesAreRejectedBeforeAdmission(){
        var engine=engine();when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response("{\"action\":\"DELEGATE\",\"answer\":\"\",\"families\":[\"fx\"]}"),response(plan("[\"fx__quote\"]","[\"a\"]")));
        assertThrows(IllegalStateException.class,()->engine.orchestrate("owner","conversation","request","Dependent task",brain));
        verify(tasks,never()).submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet());
        assertTrue(store.documents("other-owner","orchestration-invalid","").isEmpty());
    }
    @Test void currentIntentIsSeparateFromReferencesAndSingleReadDoesNotCreateWorkers(){
        var engine=engine();
        when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response("{\"action\":\"TOOLS\",\"answer\":\"\",\"families\":[\"FX\"]}"),response("{\"action\":\"TOOLS\",\"answer\":\"\",\"tools\":[\"fx__quote\"],\"agents\":[]}"));
        var context=new AutonomousOrchestration.Context("Sprawdź kurs dolara",List.of(new AutonomousOrchestration.Reference("history","USER","old","Sprawdź Gmail")),List.of(),List.of(),List.of());
        var outcome=engine.orchestrate("owner","conversation","request",context,brain);
        assertEquals("TOOLS",outcome.action());assertEquals(List.of("fx__quote"),outcome.tools());
        verify(tasks,never()).submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet());
        var calls=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider,times(2)).toolChat(any(),calls.capture(),anyList(),any());
        for(var messages:calls.getAllValues()){
            String last=((ModelMessage)messages.getLast()).content();assertTrue(last.contains("CURRENT_REQUEST"));assertTrue(last.contains("Sprawdź kurs dolara"));try{var envelope=mapper.readTree(last);assertEquals("Sprawdź kurs dolara",envelope.path("CURRENT_REQUEST").path("message").asText());assertTrue(envelope.path("REFERENCE_DATA_ONLY").toString().contains("Gmail"));}catch(Exception e){fail(e);}
        }
        assertThrows(IllegalArgumentException.class,()->engine.orchestrate("owner","conversation","request","Different message",brain));
    }

}
