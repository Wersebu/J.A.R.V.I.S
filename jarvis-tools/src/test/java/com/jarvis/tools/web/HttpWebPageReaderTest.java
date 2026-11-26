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
        assertThat(content.statusCode()).isEqualTo(200);
        assertThat(content.contentType()).contains("text/html");
    }

    @Test
    void extractsOfferDetailsFromJsonLdBeforeRemovingScripts() throws IOException {
        startServer("""
                <html>
                  <head>
                    <title>Offer page</title>
                    <script type="application/ld+json">
                      {
                        "@type": "Product",
                        "name": "Gigabyte RTX 4060 Ti Eagle 8 GB",
                        "description": "Used graphics card",
                        "offers": {
                          "@type": "Offer",
                          "price": "1199",
                          "priceCurrency": "PLN",
                          "availability": "https://schema.org/InStock"
                        }
                      }
                    </script>
                  </head>
                  <body><div>JavaScript required</div></body>
                </html>
                """);
        TestableReader reader = new TestableReader(defaultProperties());

        WebPageContent content = reader.read(baseUrl());

        assertThat(content.text()).contains("Structured data type: Product");
        assertThat(content.text()).contains("Structured data name: Gigabyte RTX 4060 Ti Eagle 8 GB");
        assertThat(content.text()).contains("Structured data offer price: 1199");
        assertThat(content.text()).contains("Structured data offer currency: PLN");
    }

    @Test
    void extractsMetaAndEmbeddedPriceHintsFromRenderedShellPages() throws IOException {
        startServer("""
                <html>
                  <head>
                    <title>OLX shell</title>
                    <meta property="og:title" content="Gigabyte RTX 4060 Ti Eagle 8 GB - OLX">
                    <meta property="og:description" content="Cena: 1250 zł. Używana karta graficzna.">
                  </head>
                  <body>
                    <script>
                      window.__APP_STATE__ = {"title":"Gigabyte RTX 4060 Ti Eagle 8 GB","regularPrice":{"value":1250,"currencyCode":"PLN"}};
                    </script>
                    <main></main>
                  </body>
                </html>
                """);
        TestableReader reader = new TestableReader(defaultProperties());

        WebPageContent content = reader.read(baseUrl());

        assertThat(content.text()).contains("Meta title: Gigabyte RTX 4060 Ti Eagle 8 GB - OLX");
        assertThat(content.text()).contains("Meta description: Cena: 1250 zł");
        assertThat(content.text()).contains("Embedded page data:");
        assertThat(content.text()).contains("Gigabyte RTX 4060 Ti Eagle 8 GB");
        assertThat(content.text()).contains("1250");
        assertThat(content.text()).contains("PLN");
    }

    @Test
    void prefersConcreteMarketplaceOffersBeforeGenericSearchLinks() throws IOException {
        startServer("""
                <html>
                  <head><title>OLX search</title></head>
                  <body>
                    <a href="/elektronika/komputery/q-rtx-3060/">Generic RTX 3060 search</a>
                    <script>
                      window.__STATE__ = {
                        "url":"https:\\u002F\\u002Fwww.olx.pl\\u002Fd\\u002Foferta\\u002Facer-predator-nvidia-geforce-rtx-3060-12gb-gddr6-karta-graficzna-CID99-ID1bKCUI.html?search_reason=search%7Cpromoted"
                      };
                    </script>
                    <a href="https://www.ceneo.pl/123456789">Ceneo concrete product</a>
                  </body>
                </html>
                """);
        TestableReader reader = new TestableReader(defaultProperties());

        WebPageContent content = reader.read(baseUrl());

        assertThat(content.links()).extracting(WebPageContent.WebPageLink::url)
                .startsWith(
                        "https://www.ceneo.pl/123456789",
                        "https://www.olx.pl/d/oferta/acer-predator-nvidia-geforce-rtx-3060-12gb-gddr6-karta-graficzna-CID99-ID1bKCUI.html?search_reason=search%7Cpromoted"
                );
    }


    @Test void followsAllowedRedirectAndReturnsFinalUrl() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var hit=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/private");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/private",exchange->{hit.set(true);write(exchange,"secret");});server.start();
        var result=new TestableReader(defaultProperties()).read(baseUrl()+"/redirect");
        assertThat(hit.get()).isTrue();assertThat(result.url()).endsWith("/private");
    }
    @Test void redirectRevalidatesPrivateDestinationBeforeContact()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var redirectReceived=new java.util.concurrent.atomic.AtomicBoolean();var hit=new java.util.concurrent.atomic.AtomicBoolean();
        server.createContext("/start",exchange->{redirectReceived.set(true);exchange.getResponseHeaders().add("Location","/blocked");exchange.sendResponseHeaders(301,-1);exchange.close();});
        server.createContext("/blocked",exchange->{hit.set(true);write(exchange,"must not be read");});server.start();
        var reader=new HttpWebPageReader(defaultProperties()){@Override protected boolean allowPrivateHosts(){return !redirectReceived.get();}};
        assertThatThrownBy(()->reader.read(baseUrl()+"/start")).isInstanceOf(WebSearchException.class).hasMessageContaining("Private or local");assertThat(hit.get()).isFalse();
    }
    @Test void redirectLoopIsBounded()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/loop",exchange->{exchange.getResponseHeaders().add("Location","/loop");exchange.sendResponseHeaders(301,-1);exchange.close();});server.start();
        assertThatThrownBy(()->new TestableReader(defaultProperties()).read(baseUrl()+"/loop")).isInstanceOf(WebSearchException.class).hasMessageContaining("loop");
    }
    @Test void rejectsOversizedResponsesAndNonpublicAddressRanges() throws Exception {
        startServer("x".repeat(2097153));
        assertThatThrownBy(()->new TestableReader(defaultProperties()).read(baseUrl())).isInstanceOf(WebSearchException.class).hasMessageContaining("2 MiB");
        for(String address:java.util.List.of("127.0.0.1","169.254.169.254","100.64.0.1","10.1.1.1","::1","fc00::1","224.0.0.1","198.18.0.1","198.19.255.254","192.0.0.1","192.0.2.1","198.51.100.1","203.0.113.1","2001:2::1","2001:db8::1","3fff::1"))
            assertThat(HttpWebPageReader.blockedAddress(java.net.InetAddress.getByName(address))).as(address).isTrue();
        assertThat(HttpWebPageReader.blockedAddress(java.net.InetAddress.getByName("8.8.8.8"))).isFalse();
    }


    @Test void rejectsDnsRebindingAtConnectionResolution() throws Exception {
        var resolutions=new java.util.concurrent.atomic.AtomicInteger();
        var reader=new HttpWebPageReader(defaultProperties()) {
            @Override protected java.net.InetAddress[] resolveAddresses(String host) throws java.net.UnknownHostException {
                return new java.net.InetAddress[]{java.net.InetAddress.getByName(resolutions.incrementAndGet()==1?"8.8.8.8":"127.0.0.1")};
            }
        };
        assertThatThrownBy(()->reader.read("http://rebinding.invalid/")).isInstanceOf(WebSearchException.class);
        assertThat(resolutions.get()).isGreaterThanOrEqualTo(2);
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
