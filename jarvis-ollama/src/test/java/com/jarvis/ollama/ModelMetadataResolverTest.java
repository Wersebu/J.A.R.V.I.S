package com.jarvis.ollama;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.HttpClient;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelMetadataResolverTest {
    @Test void architectureLimitDigestCacheAndInvalidationAreModelSpecific() throws Exception {
        var requests=new AtomicInteger();var version=new AtomicInteger(1);
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/show",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();String body=version.get()==3?"{\"model_info\":{}}":"{\"model_info\":{\"general.architecture\":\"gptoss\",\"gptoss.context_length\":"+(version.get()==1?131072:8192)+",\"unrelated.context_length\":999999}}";byte[] bytes=body.getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});
        server.createContext("/api/tags",e->{byte[] bytes=("{\"models\":[{\"name\":\"test:latest\",\"digest\":\"digest"+version.get()+"\"}]}").getBytes();e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try{
            var resolver=new ModelMetadataResolver(HttpClient.newHttpClient(),new ObjectMapper(),new OllamaProperties("http://127.0.0.1:"+server.getAddress().getPort(),"test:latest","5m",true,Set.of()));
            assertEquals(131072,resolver.resolve("test:latest").maxContext());assertEquals("digest1",resolver.resolve("test:latest").digest());assertEquals(1,requests.get());
            version.set(2);resolver.invalidate();assertEquals(8192,resolver.resolve("test:latest").maxContext());assertEquals("digest2",resolver.resolve("test:latest").digest());
            version.set(3);resolver.invalidate();assertThrows(OllamaException.class,()->resolver.resolve("test:latest"));
        }finally{server.stop(0);}
    }
}
