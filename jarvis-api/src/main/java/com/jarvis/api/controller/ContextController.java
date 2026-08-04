package com.jarvis.api.controller;

import com.jarvis.api.dto.KnowledgeRetrievalRequest;
import com.jarvis.knowledge.context.ContextBuilder;
import com.jarvis.knowledge.context.KnowledgeContext;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for building knowledge context without invoking AI.
 */
@RestController
@RequestMapping("/api/v1/context")
public class ContextController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ContextController.class);

    private final KnowledgeRetriever knowledgeRetriever;
    private final ContextBuilder contextBuilder;

    /**
     * Creates the context controller.
     *
     * @param knowledgeRetriever knowledge retriever
     * @param contextBuilder context builder
     */
    public ContextController(KnowledgeRetriever knowledgeRetriever, ContextBuilder contextBuilder) {
        this.knowledgeRetriever = knowledgeRetriever;
        this.contextBuilder = contextBuilder;
    }

    /**
     * Retrieves documents and builds structured knowledge context.
     *
     * @param request retrieval request
     * @return built knowledge context
     */
    @PostMapping("/build")
    public KnowledgeContext build(@RequestBody KnowledgeRetrievalRequest request) {
        String query = request == null ? "" : request.query();
        LOGGER.info("[JARVIS] Context build requested query=\"{}\"", query);
        RetrievalResult retrievalResult = knowledgeRetriever.retrieve(query);
        return contextBuilder.build(retrievalResult);
    }
}
