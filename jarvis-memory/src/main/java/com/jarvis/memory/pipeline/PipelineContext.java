package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.ComplexityScore;
import com.jarvis.brain.decision.ExecutionPlan;
import com.jarvis.brain.decision.KnowledgeAnalysis;
import com.jarvis.brain.decision.TaskAnalysis;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.prompt.PromptContext;
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
 * @param images resolved image attachments, ready to send to a vision-capable model
 * @param conversation conversation messages loaded for this request
 * @param memoryContext retrieved cognitive memory context
 * @param taskAnalysis task analysis
 * @param complexityScore complexity score
 * @param knowledgeAnalysis knowledge analysis
 * @param executionPlan execution plan
 * @param retrievalResult knowledge retrieval result
 * @param knowledgeContext built knowledge context
 * @param promptContext source-aware prompt context
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
        List<ImageAttachment> images,
        List<ConversationMessage> conversation,
        CognitiveMemoryContext memoryContext,
        TaskAnalysis taskAnalysis,
        ComplexityScore complexityScore,
        KnowledgeAnalysis knowledgeAnalysis,
        ExecutionPlan executionPlan,
        RetrievalResult retrievalResult,
        KnowledgeContext knowledgeContext,
        PromptContext promptContext,
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
                List.of(),
                CognitiveMemoryContext.empty(),
                null,
                null,
                null,
                null,
                null,
                KnowledgeContext.empty(),
                PromptContext.empty(),
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
                conversationId, requestId, request, images, conversation, memoryContext, taskAnalysis, complexityScore,
                knowledgeAnalysis, executionPlan, retrievalResult, knowledgeContext, promptContext, prompt, brain, model,
                response, generationFinishedEvent, Map.copyOf(updated), metadata, modelEventSink,
                cognitiveEventSink, memoryAgentFuture
        );
    }

    public PipelineContext withImages(List<ImageAttachment> value) {
        return copy(value == null ? List.of() : value, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis,
                executionPlan, retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withConversation(List<ConversationMessage> value) {
        return copy(images, value, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withTaskAnalysis(TaskAnalysis value) {
        return copy(images, conversation, memoryContext, value, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withComplexityScore(ComplexityScore value) {
        return copy(images, conversation, memoryContext, taskAnalysis, value, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withKnowledgeAnalysis(KnowledgeAnalysis value) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, value, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withExecution(ExecutionPlan plan, Brain selectedBrain) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, plan,
                retrievalResult, knowledgeContext, promptContext, prompt, selectedBrain, selectedBrain.model(), response,
                generationFinishedEvent, metadata);
    }

    public PipelineContext withRetrievalResult(RetrievalResult value) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                value, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withMemoryContext(CognitiveMemoryContext value) {
        return copy(images, conversation, value, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withKnowledgeContext(KnowledgeContext value) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, value, promptContext, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withPromptContext(PromptContext value) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, value, prompt, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withPrompt(String value) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, value, brain, model, response, generationFinishedEvent, metadata);
    }

    public PipelineContext withResponse(String value, GenerationFinishedEvent finishedEvent) {
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, value, finishedEvent, metadata);
    }

    public PipelineContext withMetadata(String key, Object value) {
        Map<String, Object> updated = new LinkedHashMap<>(metadata);
        updated.put(key, value);
        return copy(images, conversation, memoryContext, taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan,
                retrievalResult, knowledgeContext, promptContext, prompt, brain, model, response, generationFinishedEvent, Map.copyOf(updated));
    }

    public PipelineContext withMemoryAgentFuture(CompletableFuture<Void> value) {
        return new PipelineContext(
                conversationId, requestId, request, images, conversation, memoryContext, taskAnalysis, complexityScore,
                knowledgeAnalysis, executionPlan, retrievalResult, knowledgeContext, promptContext, prompt, brain, model,
                response, generationFinishedEvent, metrics, metadata, modelEventSink, cognitiveEventSink,
                value == null ? CompletableFuture.completedFuture(null) : value
        );
    }

    /**
     * Returns the effective knowledge mode selected for this request.
     *
     * @return effective knowledge mode
     */
    public KnowledgeMode effectiveKnowledgeMode() {
        Object value = metadata.get("knowledgeMode");
        if (value instanceof KnowledgeMode mode) {
            return mode;
        }
        try {
            return value == null ? KnowledgeMode.FAST : KnowledgeMode.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return KnowledgeMode.FAST;
        }
    }

    private PipelineContext copy(
            List<ImageAttachment> images,
            List<ConversationMessage> conversation,
            CognitiveMemoryContext memoryContext,
            TaskAnalysis taskAnalysis,
            ComplexityScore complexityScore,
            KnowledgeAnalysis knowledgeAnalysis,
            ExecutionPlan executionPlan,
            RetrievalResult retrievalResult,
            KnowledgeContext knowledgeContext,
            PromptContext promptContext,
            String prompt,
            Brain brain,
            String model,
            String response,
            GenerationFinishedEvent generationFinishedEvent,
            Map<String, Object> metadata
    ) {
        return new PipelineContext(
                conversationId, requestId, request, images == null ? List.of() : List.copyOf(images),
                conversation == null ? List.of() : List.copyOf(conversation),
                memoryContext == null ? CognitiveMemoryContext.empty() : memoryContext,
                taskAnalysis, complexityScore, knowledgeAnalysis, executionPlan, retrievalResult,
                knowledgeContext == null ? KnowledgeContext.empty() : knowledgeContext,
                promptContext == null ? PromptContext.empty() : promptContext,
                prompt, brain, model,
                response, generationFinishedEvent, metrics, metadata, modelEventSink, cognitiveEventSink,
                memoryAgentFuture
        );
    }
}
