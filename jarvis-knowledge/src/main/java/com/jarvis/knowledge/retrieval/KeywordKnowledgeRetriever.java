package com.jarvis.knowledge.retrieval;

import com.jarvis.common.event.KnowledgeEvent;
import com.jarvis.common.event.KnowledgeEventType;
import com.jarvis.knowledge.KnowledgeDocument;
import com.jarvis.knowledge.KnowledgeEventPublisher;
import com.jarvis.knowledge.KnowledgeIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Keyword-based retrieval implementation using indexed metadata only.
 */
@Service
@Primary
public class KeywordKnowledgeRetriever implements KnowledgeRetriever {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeywordKnowledgeRetriever.class);
    private static final int MAX_RESULTS = 10;
    private static final int TITLE_WEIGHT = 100;
    private static final int CATEGORY_WEIGHT = 60;
    private static final int FILENAME_WEIGHT = 50;
    private static final int PREVIEW_WEIGHT = 20;

    private final KnowledgeIndex knowledgeIndex;
    private final KnowledgeEventPublisher eventPublisher;

    /**
     * Creates the keyword retriever.
     *
     * @param knowledgeIndex metadata index
     * @param eventPublisher knowledge event publisher
     */
    public KeywordKnowledgeRetriever(KnowledgeIndex knowledgeIndex, KnowledgeEventPublisher eventPublisher) {
        this.knowledgeIndex = knowledgeIndex;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Retrieves documents by keyword scoring against indexed metadata.
     *
     * @param query user query
     * @return retrieval result
     */
    @Override
    public RetrievalResult retrieve(String query) {
        long started = System.nanoTime();
        String normalizedQuery = query == null ? "" : query.trim();
        eventPublisher.publish(KnowledgeEvent.retrieval(KnowledgeEventType.KNOWLEDGE_RETRIEVAL_STARTED));

        List<String> keywords = keywords(normalizedQuery);
        List<KnowledgeDocument> documents = keywords.isEmpty() ? List.of() : knowledgeIndex.list();
        List<RetrievalDocument> results = documents.stream()
                .map(document -> scoredDocument(document, keywords))
                .filter(scoredDocument -> scoredDocument.score() > 0)
                .sorted(Comparator.comparingInt(RetrievalDocument::score).reversed()
                        .thenComparing(RetrievalDocument::relativePath))
                .limit(MAX_RESULTS)
                .toList();

        long executionTimeMs = (System.nanoTime() - started) / 1_000_000;
        eventPublisher.publish(KnowledgeEvent.retrieval(KnowledgeEventType.KNOWLEDGE_RETRIEVAL_FINISHED));
        LOGGER.info("[JARVIS] Knowledge retrieval query=\"{}\" executionTimeMs={} documentsScanned={} resultsReturned={}",
                normalizedQuery,
                executionTimeMs,
                documents.size(),
                results.size());

        return new RetrievalResult(normalizedQuery, executionTimeMs, documents.size(), results);
    }

    private List<String> keywords(String query) {
        if (query.isBlank()) {
            return List.of();
        }
        return Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> !token.isBlank())
                .distinct()
                .toList();
    }

    private RetrievalDocument scoredDocument(KnowledgeDocument document, List<String> keywords) {
        int score = keywords.stream()
                .mapToInt(keyword -> score(document, keyword))
                .sum();
        return new RetrievalDocument(
                document.id(),
                document.title(),
                document.category(),
                document.relativePath(),
                score,
                document.preview()
        );
    }

    private int score(KnowledgeDocument document, String keyword) {
        return occurrences(document.title(), keyword) * TITLE_WEIGHT
                + occurrences(document.category(), keyword) * CATEGORY_WEIGHT
                + occurrences(filename(document.relativePath()), keyword) * FILENAME_WEIGHT
                + occurrences(document.preview(), keyword) * PREVIEW_WEIGHT;
    }

    private int occurrences(String value, String keyword) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalizedValue = value.toLowerCase(Locale.ROOT);
        int count = 0;
        int index = normalizedValue.indexOf(keyword);
        while (index >= 0) {
            count++;
            index = normalizedValue.indexOf(keyword, index + keyword.length());
        }
        return count;
    }

    private String filename(String relativePath) {
        int index = relativePath.lastIndexOf('/');
        return index >= 0 ? relativePath.substring(index + 1) : relativePath;
    }
}
