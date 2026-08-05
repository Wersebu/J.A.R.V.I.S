package com.jarvis.memory.research;

import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.knowledge.KnowledgeDocument;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mutable request-scoped state for agentic knowledge research.
 */
public class ResearchContext {

    private final String requestId;
    private final String conversationId;
    private final String originalQuery;
    private final String mode;
    private final ReasoningLevel reasoningLevel;
    private final List<ResearchAction> actions;
    private final List<String> observations;
    private final List<KnowledgeDocument> candidates;
    private final Set<String> candidateDocumentIds;
    private final Set<String> readDocumentIds;
    private final Set<String> usedDocumentIds;
    private final List<String> errors;
    private ResearchState currentState;
    private int stepNumber;
    private int searchCount;
    private int documentReadCount;
    private int totalCharactersRead;
    private String finalAnswer;

    /**
     * Creates research context.
     *
     * @param requestId request id
     * @param conversationId conversation id
     * @param originalQuery original query
     * @param mode mode
     * @param reasoningLevel reasoning level
     */
    public ResearchContext(String requestId, String conversationId, String originalQuery, String mode, ReasoningLevel reasoningLevel) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.originalQuery = originalQuery;
        this.mode = mode;
        this.reasoningLevel = reasoningLevel;
        this.actions = new ArrayList<>();
        this.observations = new ArrayList<>();
        this.candidates = new ArrayList<>();
        this.candidateDocumentIds = new LinkedHashSet<>();
        this.readDocumentIds = new LinkedHashSet<>();
        this.usedDocumentIds = new LinkedHashSet<>();
        this.errors = new ArrayList<>();
        this.currentState = ResearchState.PLANNING;
        this.finalAnswer = "";
    }

    public String requestId() {
        return requestId;
    }

    public String conversationId() {
        return conversationId;
    }

    public String originalQuery() {
        return originalQuery;
    }

    public String mode() {
        return mode;
    }

    public ReasoningLevel reasoningLevel() {
        return reasoningLevel;
    }

    public ResearchState currentState() {
        return currentState;
    }

    public void transitionTo(ResearchState state) {
        currentState = state;
    }

    public int nextStep() {
        stepNumber++;
        return stepNumber;
    }

    public int stepNumber() {
        return stepNumber;
    }

    public int searchCount() {
        return searchCount;
    }

    public void incrementSearchCount() {
        searchCount++;
    }

    public int documentReadCount() {
        return documentReadCount;
    }

    public void addReadDocument(String nodeId, int characters) {
        readDocumentIds.add(nodeId);
        usedDocumentIds.add(nodeId);
        documentReadCount++;
        totalCharactersRead += characters;
    }

    public int totalCharactersRead() {
        return totalCharactersRead;
    }

    public List<ResearchAction> actions() {
        return List.copyOf(actions);
    }

    public void addAction(ResearchAction action) {
        actions.add(action);
    }

    public List<String> observations() {
        return List.copyOf(observations);
    }

    public void addObservation(String observation) {
        if (observation != null && !observation.isBlank()) {
            observations.add(observation);
        }
    }

    public List<KnowledgeDocument> candidates() {
        return List.copyOf(candidates);
    }

    public void replaceCandidates(List<KnowledgeDocument> documents) {
        candidates.clear();
        candidates.addAll(documents == null ? List.of() : documents);
        for (KnowledgeDocument document : candidates) {
            candidateDocumentIds.add(nodeId(document));
        }
    }

    public Set<String> candidateDocumentIds() {
        return Set.copyOf(candidateDocumentIds);
    }

    public Set<String> readDocumentIds() {
        return Set.copyOf(readDocumentIds);
    }

    public Set<String> usedDocumentIds() {
        return Set.copyOf(usedDocumentIds);
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer == null ? "" : finalAnswer;
    }

    public List<String> errors() {
        return List.copyOf(errors);
    }

    public void addError(String error) {
        if (error != null && !error.isBlank()) {
            errors.add(error);
        }
    }

    public String observationsText() {
        return String.join(System.lineSeparator() + System.lineSeparator(), observations);
    }

    public boolean hasCandidates() {
        return !candidates.isEmpty();
    }

    public boolean hasReadContent() {
        return !readDocumentIds.isEmpty() && totalCharactersRead > 0;
    }

    public KnowledgeDocument bestCandidate() {
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    private String nodeId(KnowledgeDocument document) {
        return "knowledge-document:" + document.relativePath().replace('\\', '/');
    }
}
