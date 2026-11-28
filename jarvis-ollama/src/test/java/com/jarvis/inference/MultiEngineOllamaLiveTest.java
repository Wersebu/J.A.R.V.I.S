package com.jarvis.inference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.model.*;
import com.jarvis.ollama.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Explicit opt-in real localhost Ollama; runs no tools and changes no production configuration. */
@EnabledIfSystemProperty(named="jarvis.live.inference",matches="true")
class MultiEngineOllamaLiveTest {
 @Test void realOllamaStreamingStructuredOutputAndNativeTools()throws Exception{
  var mapper=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
  var properties=new OllamaProperties("http://127.0.0.1:11434","gpt-oss:20b","5m",true,Set.of());
  var bus=mock(CognitiveEventBus.class);var admission=new OllamaRequestCoordinator(true,bus,1,0,16,0,120000);admission.engineGate(new EngineAccessGate());
  var env=new MockEnvironment().withProperty("jarvis.inference.physical-context-window","4096").withProperty("jarvis.inference.profiles.main-conversation.output-tokens","256").withProperty("jarvis.inference.profiles.main-conversation.input-tokens","2048").withProperty("jarvis.inference.profiles.main-conversation.reasoning","LOW");
  var budgets=new ContextBudgetService(new AiContextProperties(4096,256,0),new InferenceProfileRegistry(env),new ModelMetadataResolver(HttpClient.newHttpClient(),mapper,properties));
  var active=mock(ActiveModelService.class);when(active.activeModelCapabilities()).thenReturn(Set.of(ModelCapability.TEXT,ModelCapability.THINKING,ModelCapability.TOOLS));
  var provider=new OllamaProvider(HttpClient.newHttpClient(),mapper,properties,bus,admission,new ModelWarmupRegistry(new ModelStartupProperties()),budgets,new QwenThinkingBudgetProperties(),active);
  var control=mock(InferenceControl.class);when(control.selection()).thenReturn(new InferenceControl.Selection("live-config","ollama","gpt-oss:20b",Map.of()));provider.inferenceControl(control);
  Brain brain=new Brain(BrainType.FAST,"ollama","gpt-oss:20b","Live compatibility","",0,ReasoningLevel.LOW,"live-config");
  var cases=new ArrayList<Map<String,Object>>();
  for(String mode:List.of("text","structured","tools")){
   var events=new ArrayList<ModelStreamEvent>();Map<String,Object> schema=mode.equals("structured")?Map.of("type","object","properties",Map.of("ok",Map.of("type","boolean")),"required",List.of("ok"),"additionalProperties",false):Map.of();
   var tools=mode.equals("tools")?List.of(new NativeToolDefinition("get_temperature","Return the temperature for a city",Map.of("type","object","properties",Map.of("city",Map.of("type","string")),"required",List.of("city")))):List.<NativeToolDefinition>of();
   String prompt=mode.equals("tools")?"Call get_temperature for Warsaw. Do not answer from memory.":mode.equals("structured")?"Return JSON with ok set to true.":"Reply with the single word OK.";
   long started=System.nanoTime();var result=provider.streamChat(brain,List.of(ModelMessage.user(prompt)),tools,schema,AIJobType.CHAT,events::add);long elapsed=(System.nanoTime()-started)/1000000;
   if(mode.equals("tools"))assertThat(result.toolCalls()).isNotEmpty();else assertThat(result.content()).isNotBlank();
   if(mode.equals("structured"))assertThat(mapper.readTree(result.content()).path("ok").asBoolean()).isTrue();
   cases.add(Map.of("case",mode,"status","PASSED","elapsedMs",elapsed,"usage",result.usage(),"finishReason",result.finishReason(),"streamEvents",events.size(),"toolCalls",result.toolCalls().size()));
  }
  var output=Map.of("providerMode","REAL_LOCAL_OLLAMA","engine","ollama","model","gpt-oss:20b","observedAt",Instant.now().toString(),"cases",cases,"gpuUtilizationVerified",false,"llamacppTested",false);
  Path target=Path.of(System.getProperty("jarvis.live.inference.output","data/inference-live-results.json"));Files.createDirectories(target.toAbsolutePath().getParent());Files.writeString(target,mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
 }
}
