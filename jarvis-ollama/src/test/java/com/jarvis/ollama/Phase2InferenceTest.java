package com.jarvis.ollama;

import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.model.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Phase2InferenceTest {
    @Test void backgroundSubmissionSpacingDoesNotSerializeGenerationOrDelayInteractiveAdmission() throws Exception {
        var gate=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),4,1,8,0,3000);gate.backgroundSpacing(500);
        var pool=Executors.newFixedThreadPool(2);var first=new CountDownLatch(1);var second=new CountDownLatch(1);var release=new CountDownLatch(1);
        try{
            var a=pool.submit(()->{try(var lease=gate.acquire(AIJobType.BACKGROUND,"a")){first.countDown();release.await();return true;}});
            assertTrue(first.await(1,TimeUnit.SECONDS));
            var b=pool.submit(()->{try(var lease=gate.acquire(AIJobType.BACKGROUND,"b")){second.countDown();release.await();return true;}});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(((Integer)gate.snapshot().get("waitingRequests"))==0&&System.nanoTime()<end)Thread.sleep(2);
            try(var user=gate.acquire(AIJobType.CHAT,"interactive")){assertEquals(1,second.getCount());}
            assertTrue(second.await(2,TimeUnit.SECONDS),"Second background request must start while the first generation is still active");
            assertEquals(2,gate.snapshot().get("activeInferenceRequests"));release.countDown();assertTrue(a.get(1,TimeUnit.SECONDS));assertTrue(b.get(1,TimeUnit.SECONDS));
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void fourthBackgroundWaitsWhileReservedInteractiveSlotRemainsAvailable() throws Exception {
        var gate=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),4,1,8,0,2000);
        var pool=Executors.newSingleThreadExecutor();
        try(var a=gate.acquire(AIJobType.BACKGROUND,"a");var b=gate.acquire(AIJobType.BACKGROUND,"b");var c=gate.acquire(AIJobType.BACKGROUND,"c")){
            var fourth=pool.submit(()->{try(var lease=gate.acquire(AIJobType.BACKGROUND,"d")){return true;}});
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(((Integer)gate.snapshot().get("waitingRequests"))==0&&System.nanoTime()<until)Thread.sleep(2);
            assertFalse(fourth.isDone());
            try(var interactive=gate.acquire(AIJobType.CHAT,"user")){assertEquals(4,gate.snapshot().get("activeInferenceRequests"));}
            fourth.cancel(true);
        }finally{pool.shutdownNow();pool.awaitTermination(2,TimeUnit.SECONDS);}
        assertEquals(0,gate.snapshot().get("activeInferenceRequests"));assertEquals(0,gate.snapshot().get("waitingRequests"));
    }
    @Test void configuredInteractiveProfileCannotPromoteBackgroundTask(){
        var registry=new InferenceProfileRegistry(new MockEnvironment().withProperty("jarvis.inference.profiles.research.classification","INTERACTIVE"));
        InferencePriorityContext.runBackground(()->assertTrue(registry.resolve(AIJobType.MAIN_MODEL).background()));
    }
    private final Brain brain=new Brain(BrainType.CLASSIFIER,"ollama","gpt-oss:20b","Test");
    private ContextBudgetService budget(MockEnvironment env,int max){
        var metadata=mock(ModelMetadataResolver.class);
        when(metadata.resolve(anyString())).thenReturn(new ModelMetadataResolver.Metadata("gpt-oss:20b","digest",max,System.currentTimeMillis()));
        return new ContextBudgetService(new AiContextProperties(262144,30000,1.1),new InferenceProfileRegistry(env),metadata);
    }
    @Test void logicalProfilesUseStablePhysicalContextAndExplicitOutput(){
        var service=budget(new MockEnvironment(),131072);
        InferenceProfileContext.call("conversation-title",()->{
            try(var scope=service.open(brain,AIJobType.BACKGROUND)){
                assertEquals(24576,service.ollamaOptions().get("num_ctx"));assertEquals(128,service.ollamaOptions().get("num_predict"));
                assertEquals(2048,service.currentPlan().input());assertEquals(ReasoningLevel.LOW,scope.brain(brain).reasoningLevel());
                assertTrue(service.estimateTokens(service.fitPrompt(brain.model(),"x".repeat(20000)))<=2048);
            }return null;
        });
        try(var scope=service.open(brain,AIJobType.CHAT)){
            assertEquals(24576,service.ollamaOptions().get("num_ctx"));assertTrue(service.currentPlan().input()<24576-2048);
        }
        assertNull(InferenceSettingsContext.current());
        var oversized=budget(new MockEnvironment().withProperty("jarvis.inference.physical-context-window","262144"),131072);
        try(var scope=oversized.open(brain,AIJobType.CHAT)){assertEquals(131072,oversized.ollamaOptions().get("num_ctx"));}
    }
    @Test void nativeCompactionPreservesMandatoryToolsAndRejectsOversizedSchemas(){
        var service=budget(new MockEnvironment(),131072);
        try(var scope=service.open(brain,AIJobType.CHAT)){
            var input=List.of(ModelMessage.system("System"),ModelMessage.user("old".repeat(24000)),ModelMessage.assistant("historical",List.of()),ModelMessage.user("latest"));
            var compact=service.fitMessages(input,List.of());assertTrue(compact.size()<input.size()+1);assertTrue(compact.contains(input.getLast()));assertEquals("System",compact.getFirst().content());
            assertThrows(OllamaException.class,()->service.fitMessages(List.of(ModelMessage.system("must keep"),ModelMessage.user("x".repeat(50000))),List.of()));
            assertThrows(OllamaException.class,()->service.fitMessages(List.of(ModelMessage.user("latest")),List.of(new NativeToolDefinition("large","x".repeat(50000),Map.of()))));
        }
    }
    @Test void realProviderSendsFourHttpRequestsIncludingInteractiveReservation() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverPool=Executors.newCachedThreadPool();server.setExecutor(serverPool);
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);var payloads=new CopyOnWriteArrayList<JsonNode>();
        server.createContext("/api/chat",exchange->{try{
            payloads.add(new ObjectMapper().readTree(exchange.getRequestBody()));entered.countDown();release.await(5,TimeUnit.SECONDS);
            byte[] body="{\"message\":{\"content\":\"ok\"},\"done\":true,\"eval_count\":1}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);
        }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{exchange.close();}});server.start();
        var bus=mock(CognitiveEventBus.class);var gateway=new OllamaRequestCoordinator(true,bus,4,1,16,0,3000);
        var activeModel=mock(ActiveModelService.class);when(activeModel.activeModelCapabilities()).thenReturn(Set.of());
        var provider=new OllamaProvider(HttpClient.newHttpClient(),new ObjectMapper(),new OllamaProperties("http://127.0.0.1:"+server.getAddress().getPort(),"gpt-oss:20b","5m",true,Set.of()),bus,gateway,new ModelWarmupRegistry(new ModelStartupProperties()),budget(new MockEnvironment(),131072),new QwenThinkingBudgetProperties(),activeModel);
        var clients=Executors.newFixedThreadPool(4);
        try{
            var results=new ArrayList<Future<ModelResponse>>();
            for(int i=0;i<3;i++)results.add(clients.submit(()->provider.toolChat(brain,List.of(ModelMessage.user("background")),List.of(),AIJobType.BACKGROUND)));
            results.add(clients.submit(()->provider.toolChat(brain,List.of(ModelMessage.user("conversation")),List.of(),AIJobType.CHAT)));
            assertTrue(entered.await(4,TimeUnit.SECONDS),"Four real HTTP handlers must overlap before any response is released");
            assertEquals(4,gateway.snapshot().get("activeInferenceRequests"));release.countDown();
            for(var result:results)assertEquals("ok",result.get(3,TimeUnit.SECONDS).content());
            for(var payload:payloads){assertEquals(24576,payload.path("options").path("num_ctx").asInt());assertTrue(payload.path("options").path("num_predict").asInt()>0);}
            assertEquals(0,gateway.snapshot().get("activeInferenceRequests"));
        }finally{release.countDown();clients.shutdownNow();server.stop(0);serverPool.shutdownNow();}
    }
    @Test void nestedBlockedAcquisitionFailsImmediatelyAndLeaseRecovers(){
        var gate=new OllamaRequestCoordinator(true,mock(CognitiveEventBus.class),1,0,4,0,300000);
        try(var parent=gate.acquire(AIJobType.CHAT,"parent")){
            assertTimeout(java.time.Duration.ofMillis(200),()->assertThrows(OllamaException.class,()->gate.acquire(AIJobType.CHAT,"nested")));
        }
        assertEquals(0,gate.snapshot().get("activeInferenceRequests"));
    }
}
