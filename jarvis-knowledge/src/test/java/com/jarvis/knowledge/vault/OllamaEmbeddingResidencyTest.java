package com.jarvis.knowledge.vault;
import com.jarvis.common.ai.*;
import com.jarvis.knowledge.vault.embedding.OllamaEmbeddingBackend;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OllamaEmbeddingResidencyTest {
 @Test void gpuEmbeddingCannotLoadOllamaAlongsideLlama(){
  var gateway=mock(ModelGateway.class);when(gateway.acquire(any(),any())).thenReturn(()->{});
  var backend=new OllamaEmbeddingBackend("http://127.0.0.1:1","embed",false,Duration.ofSeconds(1),gateway,()->new InferenceControl.Selection("c","llamacpp","m",Map.of()));
  assertThatThrownBy(()->backend.embed(List.of("data"))).hasMessageContaining("ENGINE_INACTIVE");verify(gateway,never()).acquireCpuAuxiliary(any(),any());
 }
 @Test void cpuEmbeddingUsesAuxiliaryLeaseAndForcesZeroGpu()throws Exception{
  var request=new AtomicReference<JsonNode>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/api/embed",e->{request.set(new ObjectMapper().readTree(e.getRequestBody()));byte[] bytes="{\"embeddings\":[[1,0]]}".getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
  try{var gateway=mock(ModelGateway.class);when(gateway.acquireCpuAuxiliary(any(),any())).thenReturn(()->{});var backend=new OllamaEmbeddingBackend("http://127.0.0.1:"+server.getAddress().getPort(),"embed",true,Duration.ofSeconds(2),gateway,()->new InferenceControl.Selection("c","llamacpp","m",Map.of()));assertThat(backend.embed(List.of("data"))).hasSize(1);assertThat(request.get().path("options").path("num_gpu").asInt(-1)).isZero();verify(gateway,never()).acquire(any(),any());}finally{server.stop(0);}
 }
}
