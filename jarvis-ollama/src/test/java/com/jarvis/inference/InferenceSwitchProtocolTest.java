package com.jarvis.inference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.model.*;
import com.jarvis.common.companion.CompanionEventSink;
import com.jarvis.ollama.OllamaProperties;
import com.sun.net.httpserver.HttpServer;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Real HTTP protocol fixture, simulated inference/process; never a GPU integration test. */
class InferenceSwitchProtocolTest {
 java.util.concurrent.atomic.AtomicBoolean ollamaUnavailable=new java.util.concurrent.atomic.AtomicBoolean();
 @TempDir Path root;HttpServer server;InferenceEngineManager manager;LlamaServerProcess process;ActiveModelService ollama;EngineAccessGate gate;InferenceProperties props;
 @BeforeEach void setup()throws Exception{
  byte[] gguf=new byte[24];System.arraycopy(new byte[]{71,71,85,70},0,gguf,0,4);Files.write(root.resolve("a.gguf"),gguf);
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();if(ollamaUnavailable.get()&&path.startsWith("/api/")){exchange.sendResponseHeaders(503,-1);exchange.close();return;}String body=path.equals("/api/ps")?"{\"models\":[]}":path.equals("/v1/models")?"{\"data\":[{\"id\":\"a.gguf\"}]}":"{}";byte[] bytes=body.getBytes();exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();});server.start();
  props=new InferenceProperties(root.resolve("selection.json"),root,root.resolve("llama-server"),server.getAddress().getPort(),true);
  process=mock(LlamaServerProcess.class);when(process.installed()).thenReturn(true);ollama=mock(ActiveModelService.class);gate=new EngineAccessGate();when(ollama.activeModel()).thenReturn("existing");when(ollama.switchTo("existing")).thenReturn(ModelSwitchResult.success("existing"));ObjectProvider<CompanionEventSink> events=mock(ObjectProvider.class);
  manager=new InferenceEngineManager(props,new ObjectMapper(),ollama,new OllamaProperties("http://127.0.0.1:"+server.getAddress().getPort(),"existing",null,true,null),gate,process,events);manager.restore();
 }
 @AfterEach void close(){server.stop(0);}
 @Test void switchesAndPersistsOnlyAfterReadiness()throws Exception{manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",4096));assertThat(manager.selection().engine()).isEqualTo("llamacpp");verify(process).awaitReady(any());assertThat(new ObjectMapper().readTree(Files.readString(props.configurationFile())).path("model").asText()).isEqualTo("a.gguf");assertThatThrownBy(()->gate.enter("ollama")).hasMessageContaining("ENGINE_INACTIVE");}
 @Test void failedLoadRollsBackAndKeepsOldSelection()throws Exception{doThrow(new AIProviderException("MODEL_LOAD_FAILED: OOM")).when(process).awaitReady(any());assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",4096))).hasMessageContaining("ENGINE_SWITCH_FAILED");assertThat(manager.selection().engine()).isEqualTo("ollama");assertThat(manager.state().get("status")).isEqualTo("READY");try(var lease=gate.enter("ollama")){}assertThat(Files.readString(props.configurationFile())).contains("existing");}
 @Test void restartPreservesConfigurationIdentity()throws Exception{manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",4096));String id=manager.selection().configurationId();manager.restore();assertThat(manager.selection().configurationId()).isEqualTo(id);}
 @Test void capabilitiesMustBeExplicit(){manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",4096));assertThatThrownBy(()->manager.validateCapabilities(List.of(),List.of(new NativeToolDefinition("t","",Map.of())),Map.of())).hasMessageContaining("CAPABILITY_REQUIRED");}
 @Test void crashIsVisibleWithoutReplayingGeneration(){manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",4096));when(process.alive()).thenReturn(false);assertThat(manager.state().get("status")).isEqualTo("ERROR");verify(process,times(1)).start(any(),any(),any());}
 @Test void unknownParameterDoesNotUnloadOrStart(){assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of("shell","whatever"))).hasMessageContaining("UNSUPPORTED_PARAMETERS");verify(process,never()).start(any(),any(),any());}
 @Test void quickSelectionPreservesApprovedParametersAcrossRestart()throws Exception{
  manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",24576,"parallelSlots",3,"reasoning","on","cacheTypeK","q8_0"));
  var approved=manager.selection().parameters();manager.restore();manager.switchTo("llamacpp","a.gguf",Map.of());
  assertThat(manager.selection().parameters()).isEqualTo(approved);
 }
 @Test void unknownModelRequiresConfiguration(){assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of())).hasMessageContaining("MODEL_CONFIGURATION_REQUIRED");verify(process,never()).start(any(),any(),any());}
 @Test void outputMustFitOneSlot(){assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",24576,"parallelSlots",3,"maxOutputTokens",8192))).hasMessageContaining("INVALID_OUTPUT_CONTEXT_BUDGET");}
 @Test void restoredLlamaRestartsWithoutAvailableOllama(){manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",24576,"parallelSlots",3));ollamaUnavailable.set(true);when(process.alive()).thenReturn(true);manager.restore();assertThat(manager.selection().engine()).isEqualTo("llamacpp");assertThat(manager.state().get("status")).isEqualTo("READY");manager.process("restart");assertThat(manager.state().get("status")).isEqualTo("READY");}
 @Test void explicitConfigurationReplacesOldOptionalSettings(){manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",24576,"parallelSlots",3,"cacheTypeK","q8_0"));manager.switchTo("llamacpp","a.gguf",Map.of("contextSize",8192));assertThat(manager.selection().parameters()).doesNotContainKey("cacheTypeK").containsEntry("parallelSlots",1);}
 @Test void detectedModelKeepsSavedCapabilityDeclarationsAfterRestart(){manager.switchTo("llamacpp","a.gguf",Map.of("declaredCapabilities",List.of("TOOLS","REASONING")));manager.restore();var row=manager.models("llamacpp").getFirst();assertThat((List<String>)row.get("declaredCapabilities")).contains("TEXT","STREAMING","TOOLS","REASONING");assertThat((List<?>)row.get("verifiedCapabilities")).isEmpty();assertThat(row).containsEntry("configurationStatus","SAVED");}
}
