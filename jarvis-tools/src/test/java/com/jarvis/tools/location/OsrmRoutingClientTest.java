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
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OsrmRoutingClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void resolvesRouteDistanceAndDuration() throws IOException {
        startServer(200, """
                {"code":"Ok","routes":[{"distance":45000.0,"duration":2400.0}]}
                """);

        RouteResult result = client().route(
                new GeoPoint(52.0, 20.9, "A"), new GeoPoint(51.9, 21.6, "B"));

        assertThat(result.resolved()).isTrue();
        assertThat(result.distanceMeters()).isEqualTo(45000.0);
        assertThat(result.durationSeconds()).isEqualTo(2400.0);
    }

    @Test
    void returnsUnresolvedWithoutThrowingWhenNoRouteFound() throws IOException {
        startServer(200, """
                {"code":"NoRoute","routes":[]}
                """);

        RouteResult result = client().route(
                new GeoPoint(52.0, 20.9, "A"), new GeoPoint(10.0, 10.0, "B"));

        assertThat(result.resolved()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    void usesLongitudeLatitudeCoordinateOrderInRequestUrl() throws IOException {
        AtomicReference<String> path = new AtomicReference<>();
        startServer(exchange -> {
            path.set(exchange.getRequestURI().getRawPath());
            write(exchange, 200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":1,\"duration\":1}]}");
        });

        // latitude=52, longitude=20.9 -> OSRM URL must carry "20.900000,52.000000" (lon,lat).
        client().route(new GeoPoint(52.0, 20.9, "A"), new GeoPoint(51.0, 21.0, "B"));

        assertThat(path.get()).contains("20.900000,52.000000");
        assertThat(path.get()).contains("21.000000,51.000000");
        assertThat(path.get()).doesNotContain("52.000000,20.900000");
    }

    @Test
    void retriesOnceOnServerErrorThenSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            if (calls.incrementAndGet() == 1) {
                write(exchange, 503, "{}");
                return;
            }
            write(exchange, 200, "{\"code\":\"Ok\",\"routes\":[{\"distance\":500,\"duration\":60}]}");
        });

        RouteResult result = client().route(new GeoPoint(52.0, 20.9, "A"), new GeoPoint(52.01, 20.91, "B"));

        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.resolved()).isTrue();
        assertThat(result.distanceMeters()).isEqualTo(500.0);
    }

    @Test
    void tableParsesMatrixWithUnreachableCellsAsNull() throws IOException {
        startServer(200, """
                {"code":"Ok","distances":[[0,1000,null],[1000,0,null],[null,null,0]],
                 "durations":[[0,120,null],[120,0,null],[null,null,0]]}
                """);

        RouteMatrixResult result = client().table(List.of(
                new GeoPoint(52.0, 20.9, "A"), new GeoPoint(52.1, 21.0, "B"), new GeoPoint(10.0, 10.0, "C")));

        assertThat(result.resolved()).isTrue();
        assertThat(result.distancesMeters()[0][1]).isEqualTo(1000.0);
        assertThat(result.distancesMeters()[0][2]).isNull();
        assertThat(result.durationsSeconds()[1][0]).isEqualTo(120.0);
    }

    private OsrmRoutingClient client() {
        LocationProperties properties = new LocationProperties(true, "unused", baseUrl(), "JARVIS-Core-LocationTool-Test/1.0",
                0, 25, 8, Duration.ofSeconds(2), Duration.ofSeconds(2), 5);
        return new OsrmRoutingClient(properties, new ObjectMapper());
    }

    private void startServer(int status, String body) throws IOException {
        startServer(exchange -> write(exchange, status, body));
    }

    private void startServer(ExchangeHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler::handle);
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
