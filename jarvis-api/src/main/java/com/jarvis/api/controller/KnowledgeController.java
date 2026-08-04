package com.jarvis.api.controller;

import com.jarvis.api.dto.KnowledgeRetrievalRequest;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeService;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST API for knowledge document metadata.
 */
@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeService knowledgeService;
    private final KnowledgeRetriever knowledgeRetriever;

    /**
     * Creates the knowledge controller.
     *
     * @param knowledgeService knowledge service
     * @param knowledgeRetriever knowledge retriever
     */
    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeRetriever knowledgeRetriever) {
        this.knowledgeService = knowledgeService;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    /**
     * Lists indexed knowledge documents.
     *
     * @return document metadata list
     */
    @GetMapping
    public List<KnowledgeDocument> listDocuments() {
        LOGGER.info("[JARVIS] Knowledge list requested");
        return knowledgeService.listDocuments();
    }

    /**
     * Gets one knowledge document by identifier.
     *
     * @param id document identifier
     * @return document metadata or 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeDocument> getDocument(@PathVariable UUID id) {
        LOGGER.info("[JARVIS] Knowledge document requested id={}", id);
        return knowledgeService.getDocument(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Rebuilds the knowledge index.
     *
     * @return rebuilt document metadata list
     */
    @PostMapping("/reindex")
    public List<KnowledgeDocument> reindex() {
        LOGGER.info("[JARVIS] Knowledge reindex requested");
        return knowledgeService.reindex();
    }

    /**
     * Retrieves knowledge documents by query.
     *
     * @param request retrieval request
     * @return retrieval result
     */
    @PostMapping("/retrieve")
    public RetrievalResult retrieve(@RequestBody KnowledgeRetrievalRequest request) {
        String query = request == null ? "" : request.query();
        LOGGER.info("[JARVIS] Knowledge retrieval requested query=\"{}\"", query);
        return knowledgeRetriever.retrieve(query);
    }
}
