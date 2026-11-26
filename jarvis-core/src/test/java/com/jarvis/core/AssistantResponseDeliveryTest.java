package com.jarvis.core;

import com.jarvis.brain.decision.*;
import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.AutonomousOrchestration;
import com.jarvis.common.diagnostics.ExecutionTracer;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.*;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.prompt.*;
import com.jarvis.core.event.DefaultCognitiveEventBus;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.conversation.ConversationTitleService;
import com.jarvis.memory.debug.PipelineDebugService;
import com.jarvis.memory.job.MemoryJobService;
import com.jarvis.memory.pipeline.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantResponseDeliveryTest {
    private final Brain brain=new Brain(BrainType.FAST,"fake","model","");
    private PipelineContext context(String request){
        return PipelineContext.initial("conversation",request,new ChatRequest("conversation","siemanko"),null,null)
            .withExecution(new ExecutionPlan(TaskType.CONVERSATION,1,1,false,0,0,BrainType.FAST,"model",ReasoningLevel.LOW,"test"),brain);
    }
    @Test void directOutcomeBypassingModelStageIsPersistedThenPublishedExactlyOnce(){
        for(String action:List.of("DIRECT","DELEGATE")){
            String id=UUID.randomUUID().toString(),answer=action.equals("DIRECT")?"Siemanko!":"Przyjęto zadania. Wyniki pojawią się tutaj.";
            var frames=new ArrayList<CognitiveEvent>();var saved=new ArrayList<ConversationMessage>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));
            var memory=mock(ConversationMemoryService.class);doAnswer(i->{saved.add(i.getArgument(1));return null;}).when(memory).addMessage(anyString(),any());
            var titles=mock(ConversationTitleService.class);var factory=new StaticListableBeanFactory();
            factory.addBean("orchestrator",(AutonomousOrchestration)(o,c,r,m,b)->new AutonomousOrchestration.Outcome(action,answer,null,List.of()));
            var semantic=new AutonomousOrchestrationStage(factory.getBeanProvider(AutonomousOrchestration.class),titles);
            var modelCalls=new AtomicInteger();var model=new PipelineStage(){public String name(){return "ModelExecutionStage";}public PipelineContext execute(PipelineContext c){modelCalls.incrementAndGet();throw new AssertionError("Semantic response must bypass generation");}};
            var validation=validation(bus,memory,titles);
            var executor=new CognitivePipelineExecutor(List.of(semantic,model,validation),bus,mock(PipelineDebugService.class));
            CurrentUserContext.runAs("owner",()->{
                bus.startRequest(id,"conversation",event->{if(event.event()==CognitiveEventType.ANSWER_TOKEN)assertEquals(1,saved.size(),"Write must precede publication");frames.add(event);});
                try {var result=executor.execute(context(id));assertEquals(answer,result.response());assertTrue((Boolean)result.metadata().get("answerPublished"));}
                finally {bus.finishRequest();}
            });
            assertEquals(0,modelCalls.get());assertEquals(1,saved.size());
            assertEquals(answer,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).map(e->e.metadata().get("text").toString()).collect(java.util.stream.Collectors.joining()));
            assertEquals(1,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_FINISHED).count());
            frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).forEach(e->{assertEquals(id,e.requestId());assertEquals("conversation",e.conversationId());assertEquals(saved.getFirst().id().toString(),e.metadata().get("messageId"));});
        }
    }
    private ResponseValidationStage validation(CognitiveEventBus bus,ConversationMemoryService memory,ConversationTitleService titles){
        var validator=mock(GroundedResponseValidator.class);when(validator.validate(anyString(),any())).thenReturn(GroundedValidationResult.success());
        return new ResponseValidationStage(bus,memory,mock(MemoryJobService.class),titles,validator,List.of());
    }
    @Test void legacyStreamingProposalsCannotDuplicateTheCommittedAnswer(){
        var frames=new ArrayList<CognitiveEvent>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));String id=UUID.randomUUID().toString();
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{
            bus.publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN","Final answer",null,Map.of("text","Final answer"));
            assertEquals(1,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).count());var message=AssistantResponse.message("owner","conversation",id,"Final answer",java.time.Instant.now());
            bus.answerCommitted(message);bus.answerCommitted(message);
        }finally{bus.finishRequest();}});
        assertEquals(1,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).count());assertEquals("Final answer",frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).findFirst().orElseThrow().message());
    }
    @Test void standardAndToolStagesShareTheSameFinalPublicationBoundary(){
        for(String stageName:List.of("ModelExecutionStage","ToolCallingStage")){
            var frames=new ArrayList<CognitiveEvent>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);String id=UUID.randomUUID().toString();
            var stage=new PipelineStage(){public String name(){return stageName;}public PipelineContext execute(PipelineContext c){bus.publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN","Verified final body",null,Map.of("text","Verified final body"));bus.publish(CognitiveEventType.ANSWER_FINISHED,"FINISHED","Old finish",null,Map.of());return c.withResponse("Verified final body",null).withMetadata("toolCallingHandled",stageName.equals("ToolCallingStage"));}};
            var executor=new CognitivePipelineExecutor(List.of(stage,validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));
            CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{executor.execute(context(id));}finally{bus.finishRequest();}});
            assertEquals(List.of("Verified final body"),frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).map(CognitiveEvent::message).toList());
            assertEquals(1,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_FINISHED).count());verify(memory,times(1)).addMessage(eq("conversation"),any());
        }
    }

    @Test void semanticDirectStreamsFromTheOriginalGenerationBeforePersistence(){
        var frames=new ArrayList<CognitiveEvent>();var saved=new ArrayList<ConversationMessage>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var provider=mock(AIProvider.class);
        when(provider.provider()).thenReturn("fake");when(provider.supportsStreamingChat()).thenReturn(true);
        when(provider.streamChat(any(),anyList(),anyList(),anyMap(),any(),any())).thenAnswer(invocation->{
            java.util.function.Consumer<ModelStreamEvent> sink=invocation.getArgument(5);
            sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.TOOL_ARGUMENTS_DELTA,"private technical data","tool","op"));
            assertTrue(frames.stream().noneMatch(e->e.event()==CognitiveEventType.ANSWER_TOKEN));
            sink.accept(ModelStreamEvent.content("{\"action\":\"DIRECT\",\"answer\":\"Pierwszy "));
            assertEquals("Pierwszy ",bus.streamedAnswer());assertTrue(saved.isEmpty());
            sink.accept(ModelStreamEvent.content("drugi\",\"tools\":[],\"agents\":[]}"));
            return new ModelResponse("{\"action\":\"DIRECT\",\"answer\":\"Pierwszy drugi\",\"tools\":[],\"agents\":[]}","",List.of(),"stop",new ModelUsage(10,8,18));
        });
        var schemas=mock(com.jarvis.tools.runtime.NativeToolSchemaMapper.class);when(schemas.definitions(any())).thenReturn(List.of());var registry=mock(com.jarvis.tools.schema.ToolRegistry.class);when(registry.definitions()).thenReturn(List.of());
        var semantic=new com.jarvis.api.companion.SemanticOrchestrator(List.of(provider),schemas,registry,mock(com.jarvis.api.companion.CompanionTasks.class),new com.jarvis.common.companion.InMemoryCompanionStore(),new com.fasterxml.jackson.databind.ObjectMapper(),true);
        org.springframework.test.util.ReflectionTestUtils.setField(semantic,"answerEvents",bus);
        var factory=new StaticListableBeanFactory();factory.addBean("orchestrator",semantic);var titles=mock(ConversationTitleService.class);
        var memory=mock(ConversationMemoryService.class);doAnswer(i->{saved.add(i.getArgument(1));return null;}).when(memory).addMessage(anyString(),any());
        var executor=new CognitivePipelineExecutor(List.of(new AutonomousOrchestrationStage(factory.getBeanProvider(AutonomousOrchestration.class),titles),validation(bus,memory,titles)),bus,mock(PipelineDebugService.class));String id=UUID.randomUUID().toString();
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{executor.execute(context(id));}finally{bus.finishRequest();}});
        assertEquals(List.of("Pierwszy ","drugi"),frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).map(CognitiveEvent::message).toList());assertEquals("Pierwszy drugi",saved.getFirst().content());
        verify(provider,times(1)).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());verify(provider,never()).toolChat(any(),anyList(),anyList(),any());
    }
    @Test void privateReasoningIsNotForwardedToTheClient(){
        var frames=new ArrayList<CognitiveEvent>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));bus.startRequest("request","conversation",frames::add);
        try{bus.publish(CognitiveEventType.THINKING_TOKEN,"THINKING","private reasoning",null,Map.of("text","private reasoning"));assertTrue(frames.isEmpty());}finally{bus.finishRequest();}
    }

    @Test void failedPersistenceCannotPublishOrFinishSuccessfully(){
        var frames=new ArrayList<CognitiveEvent>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);
        doThrow(new IllegalStateException("Database unavailable")).when(memory).addMessage(anyString(),any());
        var executor=new CognitivePipelineExecutor(List.of(validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));String id=UUID.randomUUID().toString();
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{assertThrows(IllegalStateException.class,()->executor.execute(context(id).withResponse("Nonempty model answer",null)));}finally{bus.finishRequest();}});
        assertTrue(frames.stream().noneMatch(e->e.event()==CognitiveEventType.ANSWER_TOKEN||e.event()==CognitiveEventType.REQUEST_FINISHED));
        assertTrue(frames.stream().noneMatch(e->e.event()==CognitiveEventType.PIPELINE_FINISHED&&Boolean.TRUE.equals(e.metadata().get("success"))));
    }
    @Test void aChangedFinalAnswerCannotSilentlyReplaceVisibleText(){
        var frames=new ArrayList<CognitiveEvent>();var saved=new ArrayList<ConversationMessage>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);
        doAnswer(i->{saved.add(i.getArgument(1));return null;}).when(memory).addMessage(anyString(),any());
        var executor=new CognitivePipelineExecutor(List.of(validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(executor,"failedAnswerMemory",memory);String id=UUID.randomUUID().toString();
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{
            bus.publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN","Already visible",null,Map.of("text","Already visible"));
            assertThrows(IllegalStateException.class,()->executor.execute(context(id).withResponse("Changed answer",null)));
        }finally{bus.finishRequest();}});
        assertEquals(1,saved.size());assertEquals("Already visible",saved.getFirst().content());assertEquals(com.jarvis.common.memory.ConversationMessageStatus.FAILED,saved.getFirst().status());assertTrue(frames.stream().noneMatch(e->e.event()==CognitiveEventType.ANSWER_FINISHED));
    }

    @Test void sinkFailurePropagatesAfterPersistenceInsteadOfSuccess(){
        var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);String id=UUID.randomUUID().toString();
        var executor=new CognitivePipelineExecutor(List.of(validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",e->{if(e.event()==CognitiveEventType.ANSWER_TOKEN)throw new IllegalStateException("Sink failed");});try{assertThrows(IllegalStateException.class,()->executor.execute(context(id).withResponse("Answer",null)));}finally{bus.finishRequest();}});
        verify(memory).addMessage(eq("conversation"),any());
    }
}
