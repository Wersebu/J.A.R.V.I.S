package com.jarvis.knowledge.extract;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Placeholder extractor for supported formats that are not fully parsed yet.
 */
@Component
public class UnsupportedDocumentExtractor implements DocumentExtractor {

    private static final Set<String> SUPPORTED_PLACEHOLDERS = Set.of(
            "pdf",
            "docx",
            "json",
            "xml",
            "yml",
            "yaml",
            "java",
            "properties"
    );

    /**
     * Checks whether this extractor supports an extension.
     *
     * @param extension file extension
     * @return true for future extractor formats
     */
    @Override
    public boolean supports(String extension) {
        return SUPPORTED_PLACEHOLDERS.contains(extension.toLowerCase(Locale.ROOT));
    }

    /**
     * Returns a placeholder preview.
     *
     * @param path document path
     * @param maxLength maximum preview length
     * @return placeholder preview
     */
    @Override
    public String preview(Path path, int maxLength) {
        return "Unsupported yet";
    }
}
