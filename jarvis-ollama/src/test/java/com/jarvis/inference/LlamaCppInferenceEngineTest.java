package com.jarvis.inference;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Real local HTTP, synthetic backend frames. No actual llama.cpp model is executed. */
class LlamaCppInferenceEngineTest {
 HttpServer server;LlamaCppInferenceEngine engine;InferenceEngineManager manager;AtomicReference<JsonNode> request=new AtomicReference<>();AtomicInteger calls=new AtomicInteger();String response;Brain brain;
 @BeforeEach void setup()throws Exception{
  response="data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
  server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/v1/chat/completions",e->{calls.incrementAndGet();request.set(new ObjectMapper().readTree(e.getRequestBody()));byte[] data=response.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","text/event-stream");e.sendResponseHeaders(200,data.length);e.getResponseBody().write(data);e.close();});server.start();
  var properties=new InferenceProperties(Path.of("unused"),Path.of("unused"),Path.of("unused"),server.getAddress().getPort(),true);manager=mock(InferenceEngineManager.class);when(manager.selection()).thenReturn(new InferenceControl.Selection("config-1","llamacpp","m.gguf",Map.of("timeoutSeconds",2,"temperature",0.2,"gpuLayers",0,"contextSize",4096)));
  var gate=new EngineAccessGate();try(var x=gate.exclusive()){gate.activate("llamacpp");}engine=new LlamaCppInferenceEngine(new ObjectMapper(),properties,manager,gate);brain=new Brain(BrainType.FAST,"llamacpp","m.gguf","","",0,ReasoningLevel.LOW,"config-1");
 }
 @AfterEach void close(){server.stop(0);}
 @Test void normalizesResponseAndSendsOnlyGenerationParameters(){var result=engine.toolChat(brain,List.of(ModelMessage.system("sys"),ModelMessage.user("user")),List.of(),AIJobType.CHAT);assertThat(result.content()).isEqualTo("hello");assertThat(request.get().has("gpuLayers")).isFalse();assertThat(request.get().path("temperature").asDouble()).isEqualTo(.2);assertThat(request.get().path("messages").size()).isEqualTo(2);assertThat(calls).hasValue(1);}
 @Test void preservesNativeToolHistoryAndSchema(){var tool=new ModelToolCall("call-id","search",Map.of("q","test"));engine.streamChat(brain,List.of(ModelMessage.assistant("",List.of(tool)),ModelMessage.tool("call-id","search","result")),List.of(new NativeToolDefinition("search","",Map.of("type","object"))),Map.of("type","object"),AIJobType.CHAT,e->{});assertThat(request.get().path("messages").get(1).path("tool_call_id").asText()).isEqualTo("call-id");assertThat(request.get().path("response_format").path("type").asText()).isEqualTo("json_schema");assertThat(request.get().path("messages").get(0).path("tool_calls").get(0).path("function").path("arguments").isTextual()).isTrue();}
 @Test void stalePinnedConfigurationNeverCallsBackend(){var stale=new Brain(BrainType.FAST,"llamacpp","m.gguf","","",0,ReasoningLevel.LOW,"old-config");assertThatThrownBy(()->engine.chat(stale,"x")).hasMessageContaining("STALE_CONFIGURATION");assertThat(calls).hasValue(0);}
 @Test void incompleteStreamIsNotRetried(){response="data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n";assertThatThrownBy(()->engine.chat(brain,"x")).hasMessageContaining("INCOMPLETE_GENERATION");assertThat(calls).hasValue(1);}
 @Test void capabilityErrorPreventsHttp(){doThrow(new AIProviderException("CAPABILITY_REQUIRED: TOOLS")).when(manager).validateCapabilities(anyList(),anyList(),anyMap());assertThatThrownBy(()->engine.toolChat(brain,List.of(),List.of(new NativeToolDefinition("x","",Map.of())),AIJobType.CHAT)).hasMessageContaining("CAPABILITY_REQUIRED");assertThat(calls).hasValue(0);}
 @Test void perSlotOverflowIsRejectedBeforeHttp(){when(manager.selection()).thenReturn(new InferenceControl.Selection("config-1","llamacpp","m.gguf",Map.of("contextSize",24576,"parallelSlots",3,"maxOutputTokens",1024)));assertThatThrownBy(()->engine.chat(brain,"x".repeat(24000))).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");assertThat(calls).hasValue(0);}
 @Test void forwardsVisionImageWithoutEmbeddingBase64InText(){String png="iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQ2sAAAAASUVORK5CYII=";engine.streamChat(brain,List.of(ModelMessage.user("Identify object and color",List.of(new ImageAttachment(png,"image.png")))),List.of(),Map.of(),AIJobType.CHAT,e->{});assertThat(request.get().path("messages").get(0).path("content").get(1).path("image_url").path("url").asText()).isEqualTo("data:image/png;base64,"+png);}
 @Test void preservesReasoningSeparatelyFromVisibleContent(){response="data: {\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"private reasoning\"}}]}\n\ndata: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"visible answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";var result=engine.toolChat(brain,List.of(ModelMessage.user("question")),List.of(),AIJobType.CHAT);assertThat(result.content()).isEqualTo("visible answer");assertThat(result.thinking()).isEqualTo("private reasoning");}
}
