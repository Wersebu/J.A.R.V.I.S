package com.jarvis.api.controller;

import com.jarvis.api.dto.RouterAnalyzeRequest;
import com.jarvis.api.dto.RouterAnalyzeResponse;
import com.jarvis.api.dto.RouterCompareRequest;
import com.jarvis.api.dto.RouterCompareResponse;
import com.jarvis.brain.BrainRouter;
import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.common.dto.ChatRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Debug endpoints for inspecting router decisions without invoking an AI provider.
 */
@RestController
@RequestMapping(path = "/api/v1/router", produces = MediaType.APPLICATION_JSON_VALUE)
public class RouterController {

    private final BrainRouter brainRouter;

    /**
     * Creates the router controller.
     *
     * @param brainRouter brain router
     */
    public RouterController(BrainRouter brainRouter) {
        this.brainRouter = brainRouter;
    }

    /**
     * Analyzes a single query.
     *
     * @param request analyze request
     * @return router decision
     */
    @PostMapping(path = "/analyze", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RouterAnalyzeResponse analyze(@RequestBody RouterAnalyzeRequest request) {
        return response(brainRouter.plan(new ChatRequest("router-debug", request.query())));
    }

    /**
     * Compares router decisions for multiple queries.
     *
     * @param request compare request
     * @return comparison response
     */
    @PostMapping(path = "/compare", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RouterCompareResponse compare(@RequestBody RouterCompareRequest request) {
        List<RouterAnalyzeResponse> decisions = (request.queries() == null ? List.<String>of() : request.queries())
                .stream()
                .map(query -> brainRouter.plan(new ChatRequest("router-debug", query)))
                .map(this::response)
                .toList();
        return new RouterCompareResponse(decisions);
    }

    private RouterAnalyzeResponse response(ExecutionPlan plan) {
        return new RouterAnalyzeResponse(
                plan.taskType().name(),
                plan.complexityScore(),
                plan.knowledgeRequired(),
                plan.estimatedKnowledgeDocuments(),
                plan.estimatedPromptTokens(),
                plan.selectedBrain().name(),
                plan.selectedModel(),
                plan.reasoningLevel().name(),
                plan.reason(),
                plan.confidence()
        );
    }
}
