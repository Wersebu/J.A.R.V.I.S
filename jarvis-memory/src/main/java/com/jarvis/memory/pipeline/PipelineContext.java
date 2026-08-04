package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.ComplexityScore;
import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.brain.decision.KnowledgeAnalysis;
import com.jarvis.brain.decision.TaskAnalysis;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.knowledge.retrieval.RetrievalResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import com.jarvis.common.event.CognitiveEvent;

/**
 * Immutable state passed between cognitive pipeline stages.
 *
 * @param conversationId conversation identifier
 * @param requestId request identifier
 * @param request normalized chat request
 * @param conversation conversation messages loaded for this request
 * @param memoryContext retrieved cognitive memory context
 * @param taskAnalysis task analysis
 * @param complexityScore complexity score
 * @param knowledgeAnalysis knowledge analysis
 * @param executionPlan execution plan
 * @param retrievalResult knowledge retrieval result
 * @param knowledgeContext built knowledge context
 * @param prompt prepared prompt
 * @param brain selected brain
 * @param model selected model
 * @param response generated response
 * @param generationFinishedEvent generation finished event
 * @param metrics stage metrics
 * @param metadata additional metadata
 * @param modelEventSink provider event sink
 * @param cognitiveEventSink cognitive event sink
 * @param memoryAgentFuture background memory agent future
 */
public record PipelineContext(
        String conversationId,
        String requestId,
        ChatRequest request,
        List<ConversationMessage> conversation,
        CognitiveMemoryContext memoryContext,
        TaskAnalysis taskAnalysis,
        ComplexityScore complexityScore,
        KnowledgeAnalysis knowledgeAnalysis,
        ExecutionPlan executionPlan,
        RetrievalResult retrievalResult,
        KnowledgeContext knowledgeContext,
        String prompt,
        Brain brain,
        String model,
        String response,
        GenerationFinishedEvent generationFinishedEvent,
        Map<String, StageMetric> metrics,
        Map<String, Object> metadata,
        ChatEventSink modelEventSink,
        Consumer<CognitiveEvent> cognitiveEventSink,
        CompletableFuture<Void> memoryAgentFuture
) {

    /**
     * Creates an initial pipeline context.
     *
     * @param conversationId conversation identifier
     * @param request normalized request
     * @param modelEventSink provider event sink
     * @return context
     */
    public static PipelineContext initial(
            String conversationId,
            String requestId,
            ChatRequest request,
            ChatEventSink modelEventSink,
            Consumer<CognitiveEvent> cognitiveEventSink
    ) {
        return new PipelineContext(
                conversationId,
                requestId,
                request,
                List.of(),
                CognitiveMemoryContext.empty(),
                null,
                null,
                null,
                null,
                null,
                KnowledgeContext.empty(),
                "",
                null,
                "",
                "",
                null,
                Map.of(),
                Map.of(),
                modelEventSink == null ? event -> { } : modelEventSink,
                cognitiveEventSink == null ? event -> { } : cognitiveEventSink,
                CompletableFuture.completedFuture(null)
        );
    }

    /**
     * Adds a stage metric.
     *
     * @param metric stage metric
     * @return updated context
     */
    public PipelineContext withMetric(StageMetric metric) {
        Map<String, StageMetric> updated = new LinkedHashMap<>(metrics);
        updated.put(metric.stageName(), metric);
        return new PipelineContext(
                conversationId, requestId, request, conversation, memoryContext, taskAnalysis, complexityScore,
                knowledgeAnalysis, executionPlan, retrievalResult, knowledgeContext, prompt, brain, model,
                response, generationFinishedEvent, Map.copyOf(updated), metadata, modelEventSink,
                cognitiveEventSink, memoryAgentFuture
        );
    }

    public PipelineContext withConversation(List<ConversationMessage> value) {
        return copy(value, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withTaskAnalysis(TaskAnalysis value) {
        return copy(conversation, memoryContext, value, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withComplexityScore(ComplexityScore value) {
        return copy(conversation, memoryContext, taskAnalysis, value, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withKnowledgeAnalysis(KnowledgeAnalysis value) {
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, value, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withExecution(ExecutionPlan plan, Brain selectedBrain) {
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, plan,
                retrievalResult, knowledgeContext, prompt, selectedBrain, selectedBrain.model(), response,
                generationFinishedEvent, metadata);
    }

    public PipelineContext withRetrievalResult(RetrievalResult value) {
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                value, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withMemoryContext(CognitiveMemoryContext value) {
        return copy(conversation, value, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withKnowledgeContext(KnowledgeContext value) {
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, value, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withPrompt(String value) {
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, value, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withResponse(String value, GenerationFinishedEvent finishedEvent) {
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, value, finishedEvent, metadata);
    }

    public PipelineContext withMetadata(String key, Object value) {
        Map<String, Object> updated = new LinkedHashMap<>(metadata);
        updated.put(key, value);
        return copy(conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, prompt, brain, model, response, generationFinishedEvent, Map.copyOf(updated));
    }

    public PipelineContext withMemoryAgentFuture(CompletableFuture<Void> value) {
        return new PipelineContext(
                conversationId, requestId, request, conversation, memoryContext, taskAnalysis, complexityScore,
                knowledgeAnalysis, executionPlan, retrievalResult, knowledgeContext, prompt, brain, model,
                response, generationFinishedEvent, metrics, metadata, modelEventSink, cognitiveEventSink,
                value == null ? CompletableFuture.completedFuture(null) : value
        );
    }

    private PipelineContext copy(
            List<ConversationMessage> conversation,
            CognitiveMemoryContext memoryContext,
            TaskAnalysis taskAnalysis,
            ComplexityScore complexityScore,
            KnowledgeAnalysis knowledgeAnalysis,
            ExecutionPlan executionPlan,
            RetrievalResult retrievalResult,
            KnowledgeContext knowledgeContext,
            String prompt,
            Brain brain,
            String model,
            String response,
            GenerationFinishedEvent generationFinishedEvent,
            Map<String, Object> metadata
    ) {
        return new PipelineContext(
                conversationId, requestId, request, conversation == null ? List.of() : List.copyOf(conversation),
                memoryContext == null ? CognitiveMemoryContext.empty() : memoryContext,
                taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan, retrievalResult,
                knowledgeContext == null ? KnowledgeContext.empty() : knowledgeContext, prompt, brain, model,
                response, generationFinishedEvent, metrics, metadata, modelEventSink, cognitiveEventSink,
                memoryAgentFuture
        );
    }
}
