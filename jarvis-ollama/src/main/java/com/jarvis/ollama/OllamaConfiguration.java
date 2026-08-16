package com.jarvis.ollama;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * Wires Ollama-specific infrastructure.
 */
@Configuration
@EnableConfigurationProperties({OllamaProperties.class, ModelStartupProperties.class, AiContextProperties.class, QwenThinkingBudgetProperties.class})
public class OllamaConfiguration {

    /**
     * Creates the Java HTTP client used for Ollama calls.
     *
     * @return HTTP client
     */
    @Bean
    public HttpClient ollamaHttpClient() {
        return HttpClient.newHttpClient();
    }
}
