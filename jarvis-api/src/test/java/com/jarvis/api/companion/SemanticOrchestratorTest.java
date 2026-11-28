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
    @Test void incompleteMultiOutputPlanCannotDropLiteralRequestedIdentifier()throws Exception{
        var decision=new ObjectMapper().readTree("{\"action\":\"TOOLS\",\"deliverables\":[{\"subject\":\"weather\"},{\"subject\":\"USD\"}]}");
        var failure=assertThrows(IllegalArgumentException.class,()->SemanticOrchestrator.validateRequestedIdentifiers("Pogoda, USD, bitcoin i CSS z sześciu miesięcy",decision));
        assertTrue(failure.getMessage().contains("CSS"));
        assertDoesNotThrow(()->SemanticOrchestrator.validateRequestedIdentifiers("Pogoda i USD",decision));
        assertDoesNotThrow(()->SemanticOrchestrator.validateRequestedIdentifiers("Co to CSS?",new ObjectMapper().readTree("{\"action\":\"DIRECT\",\"deliverables\":[]}")));
    }
    @Test void omittedIdentifierRepairsBeforeAnyWorkerIsAdmitted()throws Exception{
        var engine=engine();var incomplete=Map.of("action","TOOLS","answer","","families",List.of("fx"),"deliverables",List.of(Map.of("subject","USD","name","USD","goal","USD rate","families",List.of("fx"),"requiresResearch",true),Map.of("subject","weather","name","weather","goal","weather","families",List.of("fx"),"requiresResearch",false)));
        var corrected=mapper.valueToTree(incomplete);((com.fasterxml.jackson.databind.node.ArrayNode)corrected.get("deliverables")).add(mapper.valueToTree(Map.of("subject","CSS","name","CSS","goal","CSS history","families",List.of("fx"),"requiresResearch",true)));
        // Retry diagnostics are allowed, but no work may start from the incomplete decision.
        when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response(mapper.writeValueAsString(incomplete))).thenAnswer(call->{verify(tasks,never()).submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet(),anyBoolean());return response(corrected.toString());});
        when(tasks.submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet(),eq(false))).thenReturn(new CompanionTasks.Task("root","CONVERSATION","RUNNING",50,null,java.time.Instant.now(),java.time.Instant.now(),null,null));
        assertEquals("DELEGATE",engine.orchestrate("owner","conversation","coverage","Pogoda, USD i CSS",brain).action());
        verify(provider,times(2)).toolChat(any(),anyList(),anyList(),any());
    }
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

    @Test void independentResearchDeliverablesCreateTasksEvenWhenModelLabelsTheEnvelopeTools(){
        var engine=engine();var units=new ArrayList<Map<String,Object>>();for(int i=0;i<4;i++)units.add(Map.of("name","Output "+i,"goal","Independent deliverable "+i,"families",List.of("fx"),"requiresResearch",i==3,"subject","entity-"+i));
        units.add(Map.of("name","Historical detail","goal","Historical series for entity 3","families",List.of("fx"),"requiresResearch",true,"subject","entity-3"));
        try{when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response(mapper.writeValueAsString(Map.of("action","TOOLS","answer","","families",List.of("fx"),"deliverables",units))));}catch(Exception e){throw new RuntimeException(e);}
        when(tasks.submitOrchestration(anyString(),anyString(),anyString(),anyString(),anyList(),anySet(),eq(false))).thenReturn(new CompanionTasks.Task("root","CONVERSATION","RUNNING",50,null,java.time.Instant.now(),java.time.Instant.now(),null,null));
        assertEquals("DELEGATE",engine.orchestrate("owner","conversation","four","Four independent information outputs",brain).action());var agents=org.mockito.ArgumentCaptor.forClass(List.class);verify(tasks).submitOrchestration(anyString(),anyString(),anyString(),anyString(),agents.capture(),anySet(),eq(false));assertEquals(4,agents.getValue().size());assertTrue(((com.jarvis.common.companion.AutonomousOrchestration.Agent)agents.getValue().get(3)).goal().contains("Historical series"));
    }

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
        var calls=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider,times(1)).toolChat(any(),calls.capture(),anyList(),any());
        for(var messages:calls.getAllValues()){
            String last=((ModelMessage)messages.getLast()).content();assertTrue(last.contains("CURRENT_REQUEST"));assertTrue(last.contains("Sprawdź kurs dolara"));try{var envelope=mapper.readTree(last);assertEquals("Sprawdź kurs dolara",envelope.path("CURRENT_REQUEST").path("message").asText());assertTrue(envelope.path("REFERENCE_DATA_ONLY").toString().contains("Gmail"));}catch(Exception e){fail(e);}
        }
        assertThrows(IllegalArgumentException.class,()->engine.orchestrate("owner","conversation","request","Different message",brain));
    }


    @Test void mainIdentityAndKnowledgeArePresentInTheSingleDirectGeneration(){
        var engine=engine();when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response("{\"action\":\"DIRECT\",\"answer\":\"Dzień dobry.\",\"families\":[]}"));
        var context=new AutonomousOrchestration.Context("Cześć",List.of(),List.of(),List.of(),List.of(),"IDENTITY_FROM_MARKDOWN USER_PROMPT FOLDER_PROMPT KNOWLEDGE_FILES HISTORY");
        assertEquals("DIRECT",engine.orchestrate("owner","conversation","identity",context,brain).action());
        var messages=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider,times(1)).toolChat(any(),messages.capture(),anyList(),any());assertTrue(((ModelMessage)messages.getValue().getFirst()).content().contains("IDENTITY_FROM_MARKDOWN USER_PROMPT FOLDER_PROMPT KNOWLEDGE_FILES HISTORY"));
        verify(tasks,never()).submitOrchestration(any(),any(),any(),any(),anyList(),anySet());
    }
    @Test void completeRoutingIsChosenBeforeAnyExecutableTool(){
        var engine=engine();when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(response("{\"action\":\"TOOLS\",\"answer\":\"\",\"families\":[\"fx\"]}"));
        var outcome=engine.orchestrate("owner","conversation","native","Sprawdź kurs USD",brain);assertEquals("TOOLS",outcome.action());assertNull(outcome.firstResponse());assertTrue(outcome.tools().contains("fx__quote"));
        var offered=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider).toolChat(any(),anyList(),offered.capture(),any());assertEquals(1,offered.getValue().size());assertEquals("orchestration__decide",((NativeToolDefinition)offered.getValue().getFirst()).name());
    }
}
