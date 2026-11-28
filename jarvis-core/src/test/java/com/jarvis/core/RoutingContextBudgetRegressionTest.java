package com.jarvis.core;
import com.jarvis.common.ai.*;
import com.jarvis.common.companion.*;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.memory.*;
import com.jarvis.common.prompt.*;
import com.jarvis.core.service.DefaultPromptBuilder;
import com.jarvis.core.workspace.TemporaryWorkspaceProperties;
import com.jarvis.api.service.TemporaryWorkspaceService;
import com.jarvis.memory.pipeline.*;
import com.jarvis.memory.conversation.ConversationTitleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class RoutingContextBudgetRegressionTest {
 @Test void nativeSystemReusesKnowledgeWithoutPublishingDuplicateInjectionEvents(){
  var identity=mock(SystemPromptService.class);when(identity.load()).thenReturn("JARVIS");var events=mock(CognitiveEventBus.class);var builder=new DefaultPromptBuilder(identity,events,mock(TemporaryWorkspaceService.class),new TemporaryWorkspaceProperties());var knowledge=new KnowledgeContext("VERIFIED_SOURCE_CONTENT",List.of(),1,23,8,false,0);var request=new ChatRequest("c","Question");
  builder.buildPrompt(request,knowledge,CognitiveMemoryContext.empty(),PromptContext.empty());assertThat(builder.buildRoutingPrompt(request,knowledge,CognitiveMemoryContext.empty(),PromptContext.empty())).contains("VERIFIED_SOURCE_CONTENT");verify(events,times(1)).publish(eq(com.jarvis.common.event.CognitiveEventType.KNOWLEDGE_INJECTION_STARTED),any(),any(),any(),anyMap());verify(events,times(1)).publish(eq(com.jarvis.common.event.CognitiveEventType.KNOWLEDGE_INJECTION_FINISHED),any(),any(),any(),anyMap());
 }
 @Test void nativeSystemRetainsIdentityWithoutDuplicatingCurrentTurnOrHistory(){
  var identity=mock(SystemPromptService.class);when(identity.load()).thenReturn("JARVIS_PERSONALITY_KEEP");var builder=new DefaultPromptBuilder(identity,mock(CognitiveEventBus.class),mock(TemporaryWorkspaceService.class),new TemporaryWorkspaceProperties());
  var sources=new PromptContext(false,false,true,false,List.of(new GroundingSource(GroundingSourceType.CONVERSATION,"old","user","HISTORICAL_DATA_"+"x".repeat(16000),1)),PersonalQueryAnalysis.none(),ResponseMode.STANDARD);
  var request=new ChatRequest("conversation","CURRENT_DATA_"+"y".repeat(4000));String full=builder.buildPrompt(request,KnowledgeContext.empty(),CognitiveMemoryContext.empty(),sources);String system=builder.buildRoutingPrompt(request,KnowledgeContext.empty(),CognitiveMemoryContext.empty(),sources);
  assertThat(full).contains("HISTORICAL_DATA_","CURRENT_DATA_");assertThat(system).contains("JARVIS_PERSONALITY_KEEP").doesNotContain("HISTORICAL_DATA_","CURRENT_DATA_");assertThat(system.length()).isLessThan(2000);
 }
 @Test void longHistoryIsBoundedReferenceAndKeepsRecentTailFacts(){
  var captured=new AtomicReference<AutonomousOrchestration.Context>();var orchestration=new AutonomousOrchestration(){public Outcome orchestrate(String o,String c,String r,String m,Brain b){throw new AssertionError("Context missing");}public Outcome orchestrate(String o,String c,String r,Context context,Brain b){captured.set(context);return new Outcome("DIRECT","OK",null,List.of());}};
  var beans=new StaticListableBeanFactory();beans.addBean("orchestrator",orchestration);var stage=new AutonomousOrchestrationStage(beans.getBeanProvider(AutonomousOrchestration.class),mock(ConversationTitleService.class));
  var history=new ArrayList<ConversationMessage>();for(int i=1;i<=8;i++)history.add(new ConversationMessage(MessageRole.USER,"HEAD_"+i+"x".repeat(4000)+"TAIL_"+i,Instant.now()));
  String current="CURRENT_"+"z".repeat(4000);var context=PipelineContext.initial("c","r",new ChatRequest("c",current),null,null).withConversation(history).withPrompt("LEGACY_"+"x".repeat(40000)).withMetadata("nativeSystemPrompt","JARVIS_PERSONALITY_KEEP");
  stage.execute(context);var selected=captured.get();assertThat(selected.mainPrompt()).isEqualTo("JARVIS_PERSONALITY_KEEP");assertThat(selected.currentMessage()).isEqualTo(current);assertThat(selected.history()).hasSize(4);assertThat(selected.history().getLast().text()).contains("HEAD_8","TAIL_8").hasSizeLessThanOrEqualTo(400);
  assertThat(ModelContextBudget.total(List.of(ModelMessage.system(selected.mainPrompt()),ModelMessage.user(current),ModelMessage.user(selected.history().toString())),List.of())).isLessThan(3500);
 }
}
