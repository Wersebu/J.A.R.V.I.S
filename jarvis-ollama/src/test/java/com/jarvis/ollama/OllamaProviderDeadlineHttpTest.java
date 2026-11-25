package com.jarvis.ollama;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.model.ActiveModelService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.net.http.HttpClient;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaProviderDeadlineHttpTest {
    @Test void truncatedPlainGenerationFailsInsteadOfCompletingTask() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/generate",e->{e.getRequestBody().readAllBytes();byte[] body="{\"response\":\"partial\",\"done\":false}\n".getBytes();e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        var bus=mock(CognitiveEventBus.class);var gate=new OllamaRequestCoordinator(true,bus,4,1,8,0,1000);
        var active=mock(ActiveModelService.class);when(active.activeModelCapabilities()).thenReturn(Set.of());
        var provider=new OllamaProvider(HttpClient.newHttpClient(),new ObjectMapper(),new OllamaProperties("http://127.0.0.1:"+server.getAddress().getPort(),"test","5m",true,Set.of()),bus,gate,new ModelWarmupRegistry(new ModelStartupProperties()),new ContextBudgetService(new AiContextProperties(16384,2048,1.1)),new QwenThinkingBudgetProperties(),active);
        try{assertThrows(OllamaException.class,()->provider.chat(new Brain(BrainType.CLASSIFIER,"ollama","test","test"),"test"));assertEquals(0,gate.snapshot().get("activeInferenceRequests"));}
        finally{server.stop(0);}
    }
    @Test void deadlineAbortsRealStalledHttpBodyAndReleasesAdmission() throws Exception { verify(false); }
    @Test void cancellationAbortsRealStalledHttpBodyAndReleasesAdmission() throws Exception { verify(true); }
    private void verify(boolean cancel) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverPool=Executors.newCachedThreadPool();server.setExecutor(serverPool);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/api/chat",e->{try{
            e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,0);
            e.getResponseBody().write("{\"message\":{\"thinking\":\"reason\"}}\n".getBytes());e.getResponseBody().flush();
            entered.countDown();release.await(5,TimeUnit.SECONDS);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{e.close();}});server.start();
        var bus=mock(CognitiveEventBus.class);var gate=new OllamaRequestCoordinator(true,bus,4,1,8,0,3000);
        var metadata=mock(ModelMetadataResolver.class);when(metadata.resolve(anyString())).thenReturn(new ModelMetadataResolver.Metadata("test","digest",131072,System.currentTimeMillis()));
        var env=new MockEnvironment().withProperty("jarvis.inference.profiles.research.timeout-seconds",cancel?"60":"1");
        var budgets=new ContextBudgetService(new AiContextProperties(262144,30000,1.1),new InferenceProfileRegistry(env),metadata);
        var provider=new OllamaProvider(HttpClient.newHttpClient(),new ObjectMapper(),new OllamaProperties("http://127.0.0.1:"+server.getAddress().getPort(),"test","5m",true,Set.of()),bus,gate,new ModelWarmupRegistry(new ModelStartupProperties()),budgets,new QwenThinkingBudgetProperties(),mock(ActiveModelService.class));
        var clients=Executors.newSingleThreadExecutor();
        try{
            var call=clients.submit(()->{try{assertThrows(OllamaException.class,()->provider.toolChat(new Brain(BrainType.CLASSIFIER,"ollama","test","test"),List.of(ModelMessage.user("test")),List.of(),AIJobType.BACKGROUND));return true;}finally{Thread.interrupted();}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals(1,gate.snapshot().get("activeInferenceRequests"));
            if(cancel)call.cancel(true);else assertTrue(call.get(3,TimeUnit.SECONDS));
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(((Integer)gate.snapshot().get("activeInferenceRequests"))>0&&System.nanoTime()<end)Thread.sleep(5);
            assertEquals(0,gate.snapshot().get("activeInferenceRequests"));
        }finally{release.countDown();clients.shutdownNow();server.stop(0);serverPool.shutdownNow();}
    }
}
