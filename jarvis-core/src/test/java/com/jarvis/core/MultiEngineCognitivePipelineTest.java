package com.jarvis.core;

import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.*;
import com.jarvis.inference.*;
import com.jarvis.ollama.OllamaProvider;
import com.jarvis.memory.pipeline.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** All production pipeline stages and routing; deterministic provider outputs, no GPU qualification. */
@SpringBootTest(classes=JarvisApplication.class,webEnvironment=SpringBootTest.WebEnvironment.NONE,
 properties={"knowledge.watch=false","jarvis.mcp.enabled=false","jarvis.memory.background.enabled=false",
 "jarvis.memory.autonomous.enabled=false","jarvis.memory.dreaming.enabled=false","jarvis.companion.history.enabled=false",
 "jarvis.diagnostics.conversation-trace.enabled=false"})
class MultiEngineCognitivePipelineTest {
 static final Path ROOT=root();
 static Path root(){try{return Files.createTempDirectory("multi-engine-pipeline-");}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void storage(DynamicPropertyRegistry p){
  p.add("jarvis.memory.database-path",()->ROOT.resolve("memory.db").toString());
  p.add("knowledge.root",()->ROOT.resolve("knowledge").toString());
  p.add("knowledge.vault.index-database",()->ROOT.resolve("index.db").toString());
  p.add("jarvis.workspace.root",()->ROOT.resolve("workspaces").toString());
  p.add("jarvis.ai.identity-file",()->"file:"+ROOT.resolve("identity.md"));
  p.add("jarvis.model.active-model-file",()->ROOT.resolve("active-model.txt").toString());
 }
 @Autowired CognitivePipelineExecutor pipeline;
 @Autowired CognitiveEventBus bus;
 @MockBean InferenceEngineManager manager;
 @MockBean OllamaProvider ollama;
 @MockBean LlamaCppInferenceEngine llama;
 @MockBean java.net.http.HttpClient ollamaHttpClient;
 @BeforeEach void providers(){
  when(manager.contextPerRequest(any())).thenAnswer(i->{InferenceControl.Selection selected=i.getArgument(0);return ((Number)selected.parameters().getOrDefault("contextSize",24576)).intValue()/((Number)selected.parameters().getOrDefault("parallelSlots",1)).intValue();});
  when(ollama.provider()).thenReturn("ollama");when(llama.provider()).thenReturn("llamacpp");
  for(AIProvider provider:List.of(ollama,llama)){
   when(provider.supportsStreamingChat()).thenReturn(true);
   when(provider.streamChat(any(),anyList(),anyList(),anyMap(),any(),any())).thenAnswer(i->{
    Brain brain=i.getArgument(0);assertThat(brain.provider()).isEqualTo(provider.provider());assertThat(brain.configurationId()).isEqualTo(manager.selection().configurationId());
    return new ModelResponse("{\"action\":\"DIRECT\",\"answer\":\"Odpowiedź testowa.\",\"families\":[]}","",List.of(),"stop",new ModelUsage(30,10,40));
   });
   doAnswer(i->{Brain brain=i.getArgument(1);assertThat(brain.provider()).isEqualTo(provider.provider());
    ChatEventSink sink=i.getArgument(5);sink.publish(TokenEvent.create(i.getArgument(0),"{\"type\":\"FINAL_ANSWER\",\"answer\":\"Cześć!\"}"));
    sink.publish(GenerationFinishedEvent.create(i.getArgument(0),1,brain.type(),brain.model(),10,5,null));return null;
   }).when(provider).stream(anyString(),any(),anyString(),any(),anyList(),any());
  }
 }
 PipelineContext request(String message){return request(message,UUID.randomUUID().toString());}
 PipelineContext request(String message,String conversation){String id=UUID.randomUUID().toString();var result=new java.util.concurrent.atomic.AtomicReference<PipelineContext>();CurrentUserContext.runAs("engine-test",()->{
  bus.startRequest(id,conversation,e->{});try{result.set(pipeline.execute(PipelineContext.initial(conversation,id,new ChatRequest(id,message),null,null)));}finally{bus.finishRequest();}
 });return result.get();}
 @Test void completePipelineRoutesBothDirectionsAndNeverGeneratesWithInactiveProvider(){
  for(String engine:List.of("ollama","llamacpp","ollama")){
   when(manager.selection()).thenReturn(new InferenceControl.Selection(UUID.randomUUID().toString(),engine,engine.equals("ollama")?"ollama-model":"vision.gguf",Map.of("contextSize",24576,"parallelSlots",3,"declaredCapabilities",List.of("STRUCTURED_OUTPUT","TOOLS"))));
   clearInvocations(ollama,llama);
   assertThat(request("Wyjaśnij krótko czym jest algorytm.").response()).isEqualTo("Odpowiedź testowa.");
   AIProvider active=engine.equals("ollama")?ollama:llama,inactive=engine.equals("ollama")?llama:ollama;
   verify(active).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());
   verify(inactive,never()).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());
   verify(inactive,never()).toolChat(any(),anyList(),anyList(),eq(AIJobType.MAIN_MODEL));
   assertThat(InferenceSelectionContext.current()).isNull();
  }
 }
 @Test void greetingUsesSingleGenerationWithoutSemanticDecision(){
  when(manager.selection()).thenReturn(new InferenceControl.Selection("greeting","llamacpp","vision.gguf",Map.of("declaredCapabilities",List.of("TOOLS","REASONING"))));
  assertThat(request("siemka").response()).isEqualTo("Cześć!");
  verify(llama).stream(anyString(),any(),anyString(),any(),anyList(),any());
  verify(llama,never()).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());
  verify(ollama,never()).stream(anyString(),any(),anyString(),any(),anyList(),any());
 }
 @Test void taskExecutionUsesPinnedActiveEngine(){
  var selected=new InferenceControl.Selection("task-config","llamacpp","vision.gguf",Map.of());when(manager.selection()).thenReturn(selected);
  com.jarvis.common.companion.TaskExecutionContext.run("worker",()->InferenceSelectionContext.call(selected,()->{assertThat(request("siemka").response()).isEqualTo("Cześć!");return null;}));
  verify(llama).stream(anyString(),argThat(b->b.configurationId().equals("task-config")),anyString(),any(),anyList(),any());
  verify(ollama,never()).stream(anyString(),any(),anyString(),any(),anyList(),any());
 }
}
