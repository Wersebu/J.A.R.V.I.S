package com.jarvis.core;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Regression test proving the full Spring bean graph wires up cleanly - i.e. every
 * {@code @Service}/{@code @Component} constructor is unambiguously autowireable.
 *
 * <p>This non-web test catches bean-construction failures. It cannot detect servlet
 * mapping conflicts; {@link ProductionWebContextTest} additionally starts the real
 * servlet context and verifies production endpoint ownership and authentication.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class JarvisApplicationContextTest {
    // Bean wiring must never issue real inference requests during the startup runner.
    // Warmup behavior is covered by OllamaStartupModelWarmupTest with local fakes.
    @org.springframework.boot.test.mock.mockito.MockBean
    private java.net.http.HttpClient ollamaHttpClient;


    @Test
    void contextLoads() {
        // Intentionally empty: the assertion is that the ApplicationContext started at all -
        // every bean in the com.jarvis component-scan graph was successfully constructed.
    }
}
