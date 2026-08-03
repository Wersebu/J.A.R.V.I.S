package com.jarvis.knowledge.extract;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Registry for document extractors.
 */
@Service
public class DocumentExtractorRegistry {

    private final List<DocumentExtractor> extractors;

    /**
     * Creates the extractor registry.
     *
     * @param extractors available extractors
     */
    public DocumentExtractorRegistry(List<DocumentExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    /**
     * Finds an extractor by extension.
     *
     * @param extension file extension
     * @return extractor, when present
     */
    public Optional<DocumentExtractor> find(String extension) {
        return extractors.stream()
                .filter(extractor -> extractor.supports(extension))
                .findFirst();
    }
}
