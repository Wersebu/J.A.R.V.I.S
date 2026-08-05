package com.jarvis.api.controller;

import com.jarvis.common.ai.BrainType;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventSchema;
import com.jarvis.common.event.CognitiveEventType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * REST API exposing cognitive event schema and sample payloads.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    /**
     * Returns all supported cognitive event schemas.
     *
     * @return event schemas
     */
    @GetMapping("/schema")
    public List<CognitiveEventSchema> schema() {
        return List.of(
                schema(CognitiveEventType.REQUEST_RECEIVED, "Request entered the backend.", "requestId", "conversationId", "serverReceivedAt", "messageLength"),
                schema(CognitiveEventType.BRAIN_ROUTING_STARTED, "Brain routing started."),
                schema(CognitiveEventType.BRAIN_SELECTED, "Brain was selected.", "brain", "model", "reasoningLevel", "reason", "latencyMs"),
                schema(CognitiveEventType.MEMORY_SEARCH_STARTED, "Memory search started.", "query"),
                schema(CognitiveEventType.MEMORY_CANDIDATE_FOUND, "Memory candidate was found.", "type", "title", "confidence"),
                schema(CognitiveEventType.MEMORY_INJECTED, "Memory was injected into pipeline context.", "memories", "characters", "estimatedTokens"),
                schema(CognitiveEventType.MEMORY_NOT_FOUND, "No relevant memory was found.", "query", "executionTimeMs"),
                schema(CognitiveEventType.PROMPT_MEMORY_INJECTED, "Prompt contains memory section.", "memories", "memoryCharacters"),
                schema(CognitiveEventType.MEMORY_JOB_QUEUED, "Background memory job was queued.", "memoryJobId", "sourceRequestId", "conversationId", "timestamp"),
                schema(CognitiveEventType.MEMORY_AGENT_STARTED, "Background Memory Agent started.", "conversationId", "existingMemories"),
                schema(CognitiveEventType.MEMORY_AGENT_DECISION, "Background Memory Agent made a decision.", "action", "category", "priority", "confidence", "reason"),
                schema(CognitiveEventType.MEMORY_CREATED, "Memory was created.", "content", "category", "priority"),
                schema(CognitiveEventType.MEMORY_UPDATED, "Memory was updated.", "oldContent", "newContent"),
                schema(CognitiveEventType.MEMORY_DELETED, "Memory was deleted."),
                schema(CognitiveEventType.MEMORY_SKIPPED, "Memory operation was skipped.", "reason"),
                schema(CognitiveEventType.MEMORY_AGENT_FINISHED, "Background Memory Agent finished.", "durationMs"),
                schema(CognitiveEventType.MEMORY_AGENT_ERROR, "Background Memory Agent failed.", "error"),
                schema(CognitiveEventType.KNOWLEDGE_SEARCH_STARTED, "Knowledge search started.", "query"),
                schema(CognitiveEventType.DOCUMENT_FOUND, "A matching document was found.", "documentId", "title", "relativePath", "category", "score"),
                schema(CognitiveEventType.DOCUMENT_READING_STARTED, "Source document reading started.", "title", "relativePath", "category"),
                schema(CognitiveEventType.DOCUMENT_READING_FINISHED, "Source document reading finished.", "title", "relativePath", "charactersRead"),
                schema(CognitiveEventType.KNOWLEDGE_SEARCH_FINISHED, "Knowledge search finished.", "query", "documentsScanned", "resultsReturned", "executionTimeMs"),
                schema(CognitiveEventType.CONTEXT_BUILD_STARTED, "Context build started.", "retrievedDocuments"),
                schema(CognitiveEventType.SOURCE_ADDED, "Source added to context.", "title", "relativePath", "category", "charactersUsed"),
                schema(CognitiveEventType.CONTEXT_BUILD_FINISHED, "Context build finished.", "sources", "characters", "estimatedTokens", "buildTimeMs", "truncated"),
                schema(CognitiveEventType.KNOWLEDGE_INJECTION_STARTED, "Knowledge injection started.", "sources", "charactersInjected", "estimatedTokens"),
                schema(CognitiveEventType.KNOWLEDGE_INJECTION_FINISHED, "Knowledge injection finished.", "sources", "charactersInjected", "estimatedTokens"),
                schema(CognitiveEventType.PROMPT_BUILD_STARTED, "Prompt build started.", "documentsUsed"),
                schema(CognitiveEventType.PROMPT_BUILD_FINISHED, "Prompt build finished.", "promptBuildTimeMs", "promptCharacters", "estimatedPromptTokens"),
                schema(CognitiveEventType.MODEL_REQUEST_STARTED, "Model request started.", "model", "endpoint", "provider"),
                schema(CognitiveEventType.WAITING_FIRST_TOKEN, "Waiting for first token.", "model", "requestLatencyMs"),
                schema(CognitiveEventType.FIRST_TOKEN_RECEIVED, "First token received.", "latencyMs", "model"),
                schema(CognitiveEventType.THINKING_STARTED, "Native model thinking started.", "model", "reasoningLevel"),
                schema(CognitiveEventType.THINKING_TOKEN, "Native model thinking fragment.", "text", "index"),
                schema(CognitiveEventType.THINKING_FINISHED, "Native model thinking finished.", "durationMs", "chunks", "characters"),
                schema(CognitiveEventType.ANSWER_STARTED, "Final answer started.", "model", "timeToFirstAnswerTokenMs"),
                schema(CognitiveEventType.ANSWER_TOKEN, "Final answer fragment.", "text", "index"),
                schema(CognitiveEventType.ANSWER_FINISHED, "Final answer finished.", "durationMs", "characters", "tokens"),
                schema(CognitiveEventType.STREAMING_STARTED, "Streaming started.", "model"),
                schema(CognitiveEventType.TOKEN, "Generated token.", "text", "index"),
                schema(CognitiveEventType.STREAMING_FINISHED, "Streaming finished.", "generationTimeMs", "promptTokens", "completionTokens", "tokensStreamed", "tokensPerSecond"),
                schema(CognitiveEventType.REQUEST_FINISHED, "Request finished.", "generationTimeMs", "retrievalTimeMs", "contextBuildTimeMs", "promptBuildTimeMs", "documentsUsed", "tokensGenerated", "estimatedPromptTokens"),
                schema(CognitiveEventType.ERROR, "Request failed.", "exception", "message")
        );
    }

    /**
     * Returns one sample event payload for every supported event type.
     *
     * @return sample events
     */
    @GetMapping("/sample")
    public List<CognitiveEvent> sample() {
        String requestId = "sample-request";
        String conversationId = "sample-conversation";
        Instant now = Instant.now();
        return schema().stream()
                .map(schema -> sampleEvent(requestId, conversationId, now, schema))
                .toList();
    }

    private CognitiveEventSchema schema(CognitiveEventType event, String description, String... metadataFields) {
        return new CognitiveEventSchema(event, description, List.of(metadataFields));
    }

    private CognitiveEvent sampleEvent(
            String requestId,
            String conversationId,
            Instant timestamp,
            CognitiveEventSchema schema
    ) {
        return new CognitiveEvent(
                requestId,
                conversationId,
                timestamp,
                schema.event(),
                sampleStatus(schema.event()),
                sampleMessage(schema.event()),
                BrainType.REASONING,
                "gpt-oss:20b",
                sampleNodeId(schema.event()),
                sampleMetadata(schema.event())
        );
    }

    private String sampleStatus(CognitiveEventType event) {
        return event == CognitiveEventType.ERROR ? "ERROR" : "OK";
    }

    private String sampleMessage(CognitiveEventType event) {
        return event.name().replace('_', ' ');
    }

    private String sampleNodeId(CognitiveEventType event) {
        return switch (event) {
            case DOCUMENT_FOUND, DOCUMENT_READING_STARTED, DOCUMENT_READING_FINISHED, SOURCE_ADDED -> "knowledge:Spring.md";
            case BRAIN_SELECTED -> "brain:REASONING";
            case MODEL_REQUEST_STARTED, WAITING_FIRST_TOKEN, FIRST_TOKEN_RECEIVED, THINKING_STARTED, THINKING_TOKEN,
                    THINKING_FINISHED, ANSWER_STARTED, ANSWER_TOKEN, ANSWER_FINISHED, STREAMING_STARTED, TOKEN,
                    STREAMING_FINISHED -> "model:gpt-oss:20b";
            case MEMORY_JOB_QUEUED, MEMORY_AGENT_STARTED, MEMORY_AGENT_DECISION, MEMORY_AGENT_FINISHED, MEMORY_AGENT_ERROR -> "memory:agent";
            case MEMORY_CANDIDATE_FOUND, MEMORY_INJECTED, MEMORY_CREATED, MEMORY_UPDATED, MEMORY_DELETED -> "memory:sample";
            default -> null;
        };
    }

    private Map<String, Object> sampleMetadata(CognitiveEventType event) {
        return switch (event) {
            case DOCUMENT_FOUND -> Map.of("documentId", "00000000-0000-0000-0000-000000000000", "title", "Spring", "relativePath", "Java/Spring.md", "category", "Java", "score", 170);
            case SOURCE_ADDED -> Map.of("title", "Spring", "charactersUsed", 512);
            case BRAIN_SELECTED -> Map.of("brain", "REASONING", "model", "gpt-oss:20b", "reasoningLevel", "HIGH", "reason", "Sample reasoning request", "latencyMs", 3);
            case MODEL_REQUEST_STARTED -> Map.of("model", "gpt-oss:20b", "reasoningLevel", "HIGH", "endpoint", "http://localhost:11434/api/generate", "provider", "ollama");
            case MEMORY_AGENT_DECISION -> Map.of("action", "UPDATE", "category", "DEVICE", "priority", "HIGH", "confidence", 0.91, "reason", "User upgraded GPU");
            case MEMORY_JOB_QUEUED -> Map.of("memoryJobId", "00000000-0000-0000-0000-000000000001", "sourceRequestId", "sample-request", "conversationId", "sample-conversation");
            case MEMORY_AGENT_ERROR -> Map.of("error", "Memory Agent failed");
            case MEMORY_CREATED -> Map.of("content", "User owns RTX3060", "category", "DEVICE", "priority", "HIGH");
            case MEMORY_UPDATED -> Map.of("oldContent", "User owns RTX3060", "newContent", "User owns RTX5090");
            case FIRST_TOKEN_RECEIVED -> Map.of("latencyMs", 420);
            case THINKING_STARTED -> Map.of("model", "gpt-oss:20b", "reasoningLevel", "HIGH");
            case THINKING_TOKEN -> Map.of("text", "Analyzing context...", "index", 1);
            case THINKING_FINISHED -> Map.of("durationMs", 8400, "chunks", 32, "characters", 1200);
            case ANSWER_STARTED -> Map.of("model", "gpt-oss:20b", "timeToFirstAnswerTokenMs", 9217);
            case ANSWER_TOKEN -> Map.of("text", "Final answer", "index", 1);
            case ANSWER_FINISHED -> Map.of("durationMs", 4670, "characters", 700, "tokens", 128);
            case REQUEST_FINISHED -> Map.of("generationTimeMs", 1400, "retrievalTimeMs", 4, "contextBuildTimeMs", 3, "promptBuildTimeMs", 2, "documentsUsed", 2, "tokensGenerated", 64, "estimatedPromptTokens", 800);
            case TOKEN -> Map.of("text", "Hello", "index", 1);
            default -> Map.of();
        };
    }
}
