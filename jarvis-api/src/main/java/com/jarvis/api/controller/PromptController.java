package com.jarvis.api.controller;

import com.jarvis.api.dto.KnowledgeRetrievalRequest;
import com.jarvis.api.dto.SystemPromptRequest;
import com.jarvis.api.dto.SystemPromptResponse;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.prompt.PromptBuilder;
import com.jarvis.common.prompt.PromptDebugResult;
import com.jarvis.common.prompt.SystemPromptService;
import com.jarvis.knowledge.context.ContextBuilder;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * REST API for inspecting prompts without invoking AI providers.
 */
@RestController
@RequestMapping("/api/v1/prompt")
public class PromptController {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptController.class);

    private final KnowledgeRetriever knowledgeRetriever;
    private final ContextBuilder contextBuilder;
    private final PromptBuilder promptBuilder;
    private final SystemPromptService systemPromptService;

    /**
     * Creates the prompt controller.
     *
     * @param knowledgeRetriever knowledge retriever
     * @param contextBuilder context builder
     * @param promptBuilder prompt builder
     * @param systemPromptService system prompt service
     */
    public PromptController(
            KnowledgeRetriever knowledgeRetriever,
            ContextBuilder contextBuilder,
            PromptBuilder promptBuilder,
            SystemPromptService systemPromptService
    ) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
        this.systemPromptService = systemPromptService;
    }

    /**
     * Builds and returns a debug prompt view without invoking Ollama.
     *
     * @param request prompt debug request
     * @return prompt debug result
     */
    @PostMapping("/debug")
    public PromptDebugResult debug(@RequestBody KnowledgeRetrievalRequest request) {
        String query = request == null ? "" : request.query();
        LOGGER.info("[JARVIS] Prompt debug requested query=\"{}\"", query);
        RetrievalResult retrievalResult = knowledgeRetriever.retrieve(query);
        KnowledgeContext knowledgeContext = contextBuilder.build(retrievalResult);
        return promptBuilder.buildDebugPrompt(new ChatRequest("debug", query), knowledgeContext);
    }

    /**
     * Returns the editable system instructions used for future prompt builds.
     *
     * @return current system instructions
     */
    @GetMapping("/system")
    public SystemPromptResponse systemPrompt() {
        return new SystemPromptResponse(systemPromptService.load(), Instant.now());
    }

    /**
     * Updates editable system instructions used for future prompt builds.
     *
     * @param request update request
     * @return saved system instructions
     */
    @PutMapping("/system")
    public SystemPromptResponse updateSystemPrompt(@RequestBody SystemPromptRequest request) {
        String instructions = request == null ? "" : request.instructions();
        String saved = systemPromptService.save(instructions);
        return new SystemPromptResponse(saved, Instant.now());
    }
}
