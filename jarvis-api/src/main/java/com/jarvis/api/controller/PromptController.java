package com.jarvis.api.controller;

import com.jarvis.api.dto.KnowledgeRetrievalRequest;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.prompt.PromptBuilder;
import com.jarvis.common.prompt.PromptDebugResult;
import com.jarvis.knowledge.context.ContextBuilder;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * Creates the prompt controller.
     *
     * @param knowledgeRetriever knowledge retriever
     * @param contextBuilder context builder
     * @param promptBuilder prompt builder
     */
    public PromptController(
            KnowledgeRetriever knowledgeRetriever,
            ContextBuilder contextBuilder,
            PromptBuilder promptBuilder
    ) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.contextBuilder = contextBuilder;
        this.promptBuilder = promptBuilder;
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
}
