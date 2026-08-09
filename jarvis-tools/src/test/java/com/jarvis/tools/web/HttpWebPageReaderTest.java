package com.jarvis.tools.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpWebPageReaderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsLocalAddresses() {
        HttpWebPageReader reader = new HttpWebPageReader(defaultProperties());

        assertThatThrownBy(() -> reader.read("http://127.0.0.1/example"))
                .isInstanceOf(WebSearchException.class)
                .hasMessageContaining("Private or local");
    }

    @Test
    void normalizesHtmlWhenLocalProtectionIsDisabledByUsingPublicHostNameOnlyForUnitCoverage() throws IOException {
        startServer("""
                <html>
                  <head><title>GPU offer</title><style>.x{}</style><script>alert(1)</script></head>
                  <body><h1>RTX 4060 Ti</h1><p>Cena: 1299 PLN</p></body>
                </html>
                """);
        TestableReader reader = new TestableReader(defaultProperties());

        WebPageContent content = reader.read(baseUrl());

        assertThat(content.title()).isEqualTo("GPU offer");
        assertThat(content.text()).contains("RTX 4060 Ti");
        assertThat(content.text()).contains("Cena: 1299 PLN");
        assertThat(content.text()).doesNotContain("alert");
    }

    private WebSearchProperties defaultProperties() {
        return new WebSearchProperties(true, "http://127.0.0.1:8888", 5, 10, 320, 8000,
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    private void startServer(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> write(exchange, body));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void write(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class TestableReader extends HttpWebPageReader {

        private TestableReader(WebSearchProperties properties) {
            super(properties);
        }

        @Override
        protected boolean allowPrivateHosts() {
            return true;
        }
    }
}
