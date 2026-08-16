package com.jarvis.tools.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NominatimGeocodingClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesQueryToCoordinates() throws IOException {
        startServer(200, """
                [{"lat":"52.5000000","lon":"20.8000000","display_name":"Nowa Wola, Poland"}]
                """);

        GeocodeResult result = client(properties()).geocode("Nowa Wola 05-500");

        assertThat(result.resolved()).isTrue();
        assertThat(result.latitude()).isEqualTo(52.5d);
        assertThat(result.longitude()).isEqualTo(20.8d);
        assertThat(result.displayName()).isEqualTo("Nowa Wola, Poland");
    }

    @Test
    void returnsUnresolvedWithoutThrowingWhenNoMatchFound() throws IOException {
        startServer(200, "[]");

        GeocodeResult result = client(properties()).geocode("Zupelnie Nieznany Adres");

        assertThat(result.resolved()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    void sendsTheConfiguredDescriptiveUserAgent() throws IOException {
        AtomicReference<String> userAgent = new AtomicReference<>();
        startServer(exchange -> {
            userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
            write(exchange, 200, "[]");
        });

        client(properties()).geocode("Warszawa");

        assertThat(userAgent.get()).isEqualTo("JARVIS-Core-LocationTool-Test/1.0");
    }

    @Test
    void throttlesSequentialCallsToRespectTheConfiguredMinimumInterval() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            calls.incrementAndGet();
            write(exchange, 200, "[]");
        });
        LocationProperties throttled = new LocationProperties(true, baseUrl(), "unused", "JARVIS-Test/1.0",
                150, 25, 8, Duration.ofSeconds(1), Duration.ofSeconds(1));
        NominatimGeocodingClient throttledClient = new NominatimGeocodingClient(throttled, new ObjectMapper());

        long started = System.nanoTime();
        throttledClient.geocode("A");
        throttledClient.geocode("B");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertThat(calls.get()).isEqualTo(2);
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(140L);
    }

    @Test
    void mapsServerErrorToLocationException() throws IOException {
        startServer(500, "{}");

        assertThatThrownBy(() -> client(properties()).geocode("Warszawa"))
                .isInstanceOf(LocationException.class);
    }

    private NominatimGeocodingClient client(LocationProperties properties) {
        return new NominatimGeocodingClient(properties, new ObjectMapper());
    }

    private LocationProperties properties() {
        return new LocationProperties(true, baseUrl(), "unused", "JARVIS-Core-LocationTool-Test/1.0",
                0, 25, 8, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private void startServer(int status, String body) throws IOException {
        startServer(exchange -> write(exchange, status, body));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", handler::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
