package com.jarvis.api.controller;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.diagnostics.InferenceDiagnostics;
import com.jarvis.common.diagnostics.InferenceDiagnosticsContext;
import com.jarvis.common.diagnostics.InferenceDiagnosticsService;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.prompt.SystemPromptService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Developer-only raw model benchmark endpoint.
 */
@RestController
@RequestMapping(path = "/api/v1/debug/model", produces = MediaType.APPLICATION_JSON_VALUE)
public class DebugModelBenchmarkController {

    private final List<AIProvider> aiProviders;
    private final InferenceDiagnosticsService diagnosticsService;
    private final SystemPromptService systemPromptService;
    private final boolean exposeDebugApi;
    private final String defaultModel;

    /**
     * Creates the model benchmark controller.
     *
     * @param aiProviders available AI providers
     * @param diagnosticsService diagnostics service
     * @param systemPromptService system prompt service
     * @param exposeDebugApi whether debug endpoints are enabled
     * @param defaultModel default benchmark model
     */
    public DebugModelBenchmarkController(
            List<AIProvider> aiProviders,
            InferenceDiagnosticsService diagnosticsService,
            SystemPromptService systemPromptService,
            @Value("${jarvis.diagnostics.expose-debug-api:false}") boolean exposeDebugApi,
            @Value("${jarvis.ollama.model:gpt-oss:20b}") String defaultModel
    ) {
        this.aiProviders = List.copyOf(aiProviders);
        this.diagnosticsService = diagnosticsService;
        this.systemPromptService = systemPromptService;
        this.exposeDebugApi = exposeDebugApi;
        this.defaultModel = defaultModel;
    }

    /**
     * Runs one raw model benchmark outside the normal prompt pipeline.
     *
     * @param request benchmark request
     * @return benchmark response
     */
    @PostMapping(path = "/benchmark", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ModelBenchmarkResponse benchmark(@RequestBody ModelBenchmarkRequest request) {
        if (!exposeDebugApi) {
            throw new ResponseStatusException(NOT_FOUND, "Debug model benchmark is disabled");
        }
        UUID requestId = UUID.randomUUID();
        InferenceDiagnostics diagnostics = diagnosticsService.create(requestId, "debug-model-benchmark");
        InferenceDiagnosticsContext.bind(diagnostics);
        try {
            String model = request.model() == null || request.model().isBlank() ? defaultModel : request.model().strip();
            ReasoningLevel reasoningLevel = request.reasoningLevel() == null ? ReasoningLevel.LOW : request.reasoningLevel();
            String prompt = request.prompt() == null ? "" : request.prompt();
            if (request.includeSystemPrompt()) {
                prompt = systemPromptService.load() + "\n\n=== USER MESSAGE ===\n" + prompt;
            }
            Brain brain = new Brain(BrainType.FAST, "ollama", model, "Debug raw model benchmark")
                    .withRoutingMetadata("Debug raw model benchmark", 0L, reasoningLevel);
            ChatResponse response = selectProvider().chat(brain, prompt, AIJobType.DEBUG);
            return new ModelBenchmarkResponse(model, reasoningLevel, response.response(), diagnostics);
        } finally {
            InferenceDiagnosticsContext.clear();
        }
    }

    private AIProvider selectProvider() {
        return aiProviders.stream()
                .filter(provider -> "ollama".equalsIgnoreCase(provider.provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("Ollama provider is not available"));
    }

    /**
     * Raw model benchmark request.
     *
     * @param model model name
     * @param prompt raw prompt
     * @param reasoningLevel reasoning level
     * @param includeSystemPrompt whether editable system prompt should be prepended
     */
    public record ModelBenchmarkRequest(
            String model,
            String prompt,
            ReasoningLevel reasoningLevel,
            boolean includeSystemPrompt
    ) {
    }

    /**
     * Raw model benchmark response.
     *
     * @param model model name
     * @param reasoningLevel reasoning level
     * @param response model response
     * @param diagnostics captured diagnostics
     */
    public record ModelBenchmarkResponse(
            String model,
            ReasoningLevel reasoningLevel,
            String response,
            InferenceDiagnostics diagnostics
    ) {
    }
}
