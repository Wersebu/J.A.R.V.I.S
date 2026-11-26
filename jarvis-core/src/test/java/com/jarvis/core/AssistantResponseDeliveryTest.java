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
            bus.publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN","stale text",null,Map.of("text","stale text"));
            assertTrue(frames.isEmpty());var message=AssistantResponse.message("owner","conversation",id,"Final answer",java.time.Instant.now());
            bus.answerCommitted(message);bus.answerCommitted(message);
        }finally{bus.finishRequest();}});
        assertEquals(1,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).count());assertEquals("Final answer",frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).findFirst().orElseThrow().message());
    }
    @Test void standardAndToolStagesShareTheSameFinalPublicationBoundary(){
        for(String stageName:List.of("ModelExecutionStage","ToolCallingStage")){
            var frames=new ArrayList<CognitiveEvent>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);String id=UUID.randomUUID().toString();
            var stage=new PipelineStage(){public String name(){return stageName;}public PipelineContext execute(PipelineContext c){bus.publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN","draft",null,Map.of("text","draft"));bus.publish(CognitiveEventType.ANSWER_FINISHED,"FINISHED","Old finish",null,Map.of());return c.withResponse("Verified final body",null).withMetadata("toolCallingHandled",stageName.equals("ToolCallingStage"));}};
            var executor=new CognitivePipelineExecutor(List.of(stage,validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));
            CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{executor.execute(context(id));}finally{bus.finishRequest();}});
            assertEquals(List.of("Verified final body"),frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_TOKEN).map(CognitiveEvent::message).toList());
            assertEquals(1,frames.stream().filter(e->e.event()==CognitiveEventType.ANSWER_FINISHED).count());verify(memory,times(1)).addMessage(eq("conversation"),any());
        }
    }

    @Test void failedPersistenceCannotPublishOrFinishSuccessfully(){
        var frames=new ArrayList<CognitiveEvent>();var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);
        doThrow(new IllegalStateException("Database unavailable")).when(memory).addMessage(anyString(),any());
        var executor=new CognitivePipelineExecutor(List.of(validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));String id=UUID.randomUUID().toString();
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",frames::add);try{assertThrows(IllegalStateException.class,()->executor.execute(context(id).withResponse("Nonempty model answer",null)));}finally{bus.finishRequest();}});
        assertTrue(frames.stream().noneMatch(e->e.event()==CognitiveEventType.ANSWER_TOKEN||e.event()==CognitiveEventType.REQUEST_FINISHED));
        assertTrue(frames.stream().noneMatch(e->e.event()==CognitiveEventType.PIPELINE_FINISHED&&Boolean.TRUE.equals(e.metadata().get("success"))));
    }
    @Test void sinkFailurePropagatesAfterPersistenceInsteadOfSuccess(){
        var bus=new DefaultCognitiveEventBus(mock(ExecutionTracer.class));var memory=mock(ConversationMemoryService.class);String id=UUID.randomUUID().toString();
        var executor=new CognitivePipelineExecutor(List.of(validation(bus,memory,mock(ConversationTitleService.class))),bus,mock(PipelineDebugService.class));
        CurrentUserContext.runAs("owner",()->{bus.startRequest(id,"conversation",e->{if(e.event()==CognitiveEventType.ANSWER_TOKEN)throw new IllegalStateException("Sink failed");});try{assertThrows(IllegalStateException.class,()->executor.execute(context(id).withResponse("Answer",null)));}finally{bus.finishRequest();}});
        verify(memory).addMessage(eq("conversation"),any());
    }
}
