package com.jarvis.common.diagnostics;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-request inference timing diagnostics.
 */
public class InferenceDiagnostics {

    private final UUID requestId;
    private final String conversationId;
    private final long createdNanoTime;
    private Instant clientRequestTimestamp;
    private Instant serverReceivedTimestamp;
    private Long clientToServerMs;
    private Long controllerDispatchMs;
    private Long executorQueueWaitMs;
    private Long pipelineStartupMs;
    private Long validationMs;
    private Long taskAnalysisMs;
    private Long complexityAnalysisMs;
    private Long memoryRetrievalMs;
    private Long knowledgeRetrievalMs;
    private Long contextBuildMs;
    private Long promptBuildMs;
    private Integer promptCharacters;
    private Integer estimatedPromptTokens;
    private Integer systemPromptChars;
    private Integer conversationContextChars;
    private Integer knowledgeContextChars;
    private Integer toolCapabilityChars;
    private Integer currentUserMessageChars;
    private Integer totalPromptChars;
    private Integer actualPromptTokens;
    private Integer memoryItemsInjected;
    private Integer knowledgeDocumentsInjected;
    private Long ollamaPermitQueueWaitMs;
    private Long httpClientPreparationMs;
    private Long dnsResolutionMs;
    private Long tcpConnectMs;
    private Long requestHeadersSentMs;
    private Long requestBodySentMs;
    private Long responseHeadersReceivedMs;
    private Long firstResponseByteMs;
    private Long firstThinkingTokenMs;
    private Long thinkingDurationMs;
    private Long thinkingTokensOrChunks;
    private Long firstAnswerTokenMs;
    private Long answerStreamingDurationMs;
    private Long totalModelRequestMs;
    private Long totalRequestMs;
    private String model;
    private String reasoningLevel;
    private Boolean modelWasAlreadyLoaded;
    private Boolean requestWaitedForAnotherOllamaJob;
    private String ollamaJobTypeBlockingRequest;
    private OllamaInferenceMetrics ollamaMetrics;

    /**
     * Creates diagnostics for one request.
     *
     * @param requestId request id
     * @param conversationId conversation id
     */
    public InferenceDiagnostics(UUID requestId, String conversationId) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.createdNanoTime = System.nanoTime();
    }

    public UUID getRequestId() {
        return requestId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public long getCreatedNanoTime() {
        return createdNanoTime;
    }

    public Instant getClientRequestTimestamp() {
        return clientRequestTimestamp;
    }

    public void setClientRequestTimestamp(Instant clientRequestTimestamp) {
        this.clientRequestTimestamp = clientRequestTimestamp;
    }

    public Instant getServerReceivedTimestamp() {
        return serverReceivedTimestamp;
    }

    public void setServerReceivedTimestamp(Instant serverReceivedTimestamp) {
        this.serverReceivedTimestamp = serverReceivedTimestamp;
    }

    public Long getClientToServerMs() {
        return clientToServerMs;
    }

    public void setClientToServerMs(Long clientToServerMs) {
        this.clientToServerMs = clientToServerMs;
    }

    public Long getControllerDispatchMs() {
        return controllerDispatchMs;
    }

    public void setControllerDispatchMs(Long controllerDispatchMs) {
        this.controllerDispatchMs = controllerDispatchMs;
    }

    public Long getExecutorQueueWaitMs() {
        return executorQueueWaitMs;
    }

    public void setExecutorQueueWaitMs(Long executorQueueWaitMs) {
        this.executorQueueWaitMs = executorQueueWaitMs;
    }

    public Long getPipelineStartupMs() {
        return pipelineStartupMs;
    }

    public void setPipelineStartupMs(Long pipelineStartupMs) {
        this.pipelineStartupMs = pipelineStartupMs;
    }

    public Long getValidationMs() {
        return validationMs;
    }

    public void setValidationMs(Long validationMs) {
        this.validationMs = validationMs;
    }

    public Long getTaskAnalysisMs() {
        return taskAnalysisMs;
    }

    public void setTaskAnalysisMs(Long taskAnalysisMs) {
        this.taskAnalysisMs = taskAnalysisMs;
    }

    public Long getComplexityAnalysisMs() {
        return complexityAnalysisMs;
    }

    public void setComplexityAnalysisMs(Long complexityAnalysisMs) {
        this.complexityAnalysisMs = complexityAnalysisMs;
    }

    public Long getMemoryRetrievalMs() {
        return memoryRetrievalMs;
    }

    public void setMemoryRetrievalMs(Long memoryRetrievalMs) {
        this.memoryRetrievalMs = memoryRetrievalMs;
    }

    public Long getKnowledgeRetrievalMs() {
        return knowledgeRetrievalMs;
    }

    public void setKnowledgeRetrievalMs(Long knowledgeRetrievalMs) {
        this.knowledgeRetrievalMs = knowledgeRetrievalMs;
    }

    public Long getContextBuildMs() {
        return contextBuildMs;
    }

    public void setContextBuildMs(Long contextBuildMs) {
        this.contextBuildMs = contextBuildMs;
    }

    public Long getPromptBuildMs() {
        return promptBuildMs;
    }

    public void setPromptBuildMs(Long promptBuildMs) {
        this.promptBuildMs = promptBuildMs;
    }

    public Integer getPromptCharacters() {
        return promptCharacters;
    }

    public void setPromptCharacters(Integer promptCharacters) {
        this.promptCharacters = promptCharacters;
    }

    public Integer getEstimatedPromptTokens() {
        return estimatedPromptTokens;
    }

    public void setEstimatedPromptTokens(Integer estimatedPromptTokens) {
        this.estimatedPromptTokens = estimatedPromptTokens;
    }

    public Integer getSystemPromptChars() {
        return systemPromptChars;
    }

    public void setSystemPromptChars(Integer systemPromptChars) {
        this.systemPromptChars = systemPromptChars;
    }

    public Integer getConversationContextChars() {
        return conversationContextChars;
    }

    public void setConversationContextChars(Integer conversationContextChars) {
        this.conversationContextChars = conversationContextChars;
    }

    public Integer getKnowledgeContextChars() {
        return knowledgeContextChars;
    }

    public void setKnowledgeContextChars(Integer knowledgeContextChars) {
        this.knowledgeContextChars = knowledgeContextChars;
    }

    public Integer getToolCapabilityChars() {
        return toolCapabilityChars;
    }

    public void setToolCapabilityChars(Integer toolCapabilityChars) {
        this.toolCapabilityChars = toolCapabilityChars;
    }

    public Integer getCurrentUserMessageChars() {
        return currentUserMessageChars;
    }

    public void setCurrentUserMessageChars(Integer currentUserMessageChars) {
        this.currentUserMessageChars = currentUserMessageChars;
    }

    public Integer getTotalPromptChars() {
        return totalPromptChars;
    }

    public void setTotalPromptChars(Integer totalPromptChars) {
        this.totalPromptChars = totalPromptChars;
    }

    public Integer getActualPromptTokens() {
        return actualPromptTokens;
    }

    public void setActualPromptTokens(Integer actualPromptTokens) {
        this.actualPromptTokens = actualPromptTokens;
    }

    public Integer getMemoryItemsInjected() {
        return memoryItemsInjected;
    }

    public void setMemoryItemsInjected(Integer memoryItemsInjected) {
        this.memoryItemsInjected = memoryItemsInjected;
    }

    public Integer getKnowledgeDocumentsInjected() {
        return knowledgeDocumentsInjected;
    }

    public void setKnowledgeDocumentsInjected(Integer knowledgeDocumentsInjected) {
        this.knowledgeDocumentsInjected = knowledgeDocumentsInjected;
    }

    public Long getOllamaPermitQueueWaitMs() {
        return ollamaPermitQueueWaitMs;
    }

    public void setOllamaPermitQueueWaitMs(Long ollamaPermitQueueWaitMs) {
        this.ollamaPermitQueueWaitMs = ollamaPermitQueueWaitMs;
    }

    public Long getHttpClientPreparationMs() {
        return httpClientPreparationMs;
    }

    public void setHttpClientPreparationMs(Long httpClientPreparationMs) {
        this.httpClientPreparationMs = httpClientPreparationMs;
    }

    public Long getDnsResolutionMs() {
        return dnsResolutionMs;
    }

    public Long getTcpConnectMs() {
        return tcpConnectMs;
    }

    public Long getRequestHeadersSentMs() {
        return requestHeadersSentMs;
    }

    public Long getRequestBodySentMs() {
        return requestBodySentMs;
    }

    public Long getResponseHeadersReceivedMs() {
        return responseHeadersReceivedMs;
    }

    public void setResponseHeadersReceivedMs(Long responseHeadersReceivedMs) {
        this.responseHeadersReceivedMs = responseHeadersReceivedMs;
    }

    public Long getFirstResponseByteMs() {
        return firstResponseByteMs;
    }

    public void setFirstResponseByteMs(Long firstResponseByteMs) {
        this.firstResponseByteMs = firstResponseByteMs;
    }

    public Long getFirstThinkingTokenMs() {
        return firstThinkingTokenMs;
    }

    public void setFirstThinkingTokenMs(Long firstThinkingTokenMs) {
        this.firstThinkingTokenMs = firstThinkingTokenMs;
    }

    public Long getThinkingDurationMs() {
        return thinkingDurationMs;
    }

    public void setThinkingDurationMs(Long thinkingDurationMs) {
        this.thinkingDurationMs = thinkingDurationMs;
    }

    public Long getThinkingTokensOrChunks() {
        return thinkingTokensOrChunks;
    }

    public void setThinkingTokensOrChunks(Long thinkingTokensOrChunks) {
        this.thinkingTokensOrChunks = thinkingTokensOrChunks;
    }

    public Long getFirstAnswerTokenMs() {
        return firstAnswerTokenMs;
    }

    public void setFirstAnswerTokenMs(Long firstAnswerTokenMs) {
        this.firstAnswerTokenMs = firstAnswerTokenMs;
    }

    public Long getAnswerStreamingDurationMs() {
        return answerStreamingDurationMs;
    }

    public void setAnswerStreamingDurationMs(Long answerStreamingDurationMs) {
        this.answerStreamingDurationMs = answerStreamingDurationMs;
    }

    public Long getTotalModelRequestMs() {
        return totalModelRequestMs;
    }

    public void setTotalModelRequestMs(Long totalModelRequestMs) {
        this.totalModelRequestMs = totalModelRequestMs;
    }

    public Long getTotalRequestMs() {
        return totalRequestMs;
    }

    public void setTotalRequestMs(Long totalRequestMs) {
        this.totalRequestMs = totalRequestMs;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getReasoningLevel() {
        return reasoningLevel;
    }

    public void setReasoningLevel(String reasoningLevel) {
        this.reasoningLevel = reasoningLevel;
    }

    public Boolean getModelWasAlreadyLoaded() {
        return modelWasAlreadyLoaded;
    }

    public void setModelWasAlreadyLoaded(Boolean modelWasAlreadyLoaded) {
        this.modelWasAlreadyLoaded = modelWasAlreadyLoaded;
    }

    public Boolean getRequestWaitedForAnotherOllamaJob() {
        return requestWaitedForAnotherOllamaJob;
    }

    public void setRequestWaitedForAnotherOllamaJob(Boolean requestWaitedForAnotherOllamaJob) {
        this.requestWaitedForAnotherOllamaJob = requestWaitedForAnotherOllamaJob;
    }

    public String getOllamaJobTypeBlockingRequest() {
        return ollamaJobTypeBlockingRequest;
    }

    public void setOllamaJobTypeBlockingRequest(String ollamaJobTypeBlockingRequest) {
        this.ollamaJobTypeBlockingRequest = ollamaJobTypeBlockingRequest;
    }

    public OllamaInferenceMetrics getOllamaMetrics() {
        return ollamaMetrics;
    }

    public void setOllamaMetrics(OllamaInferenceMetrics ollamaMetrics) {
        this.ollamaMetrics = ollamaMetrics;
    }
}
