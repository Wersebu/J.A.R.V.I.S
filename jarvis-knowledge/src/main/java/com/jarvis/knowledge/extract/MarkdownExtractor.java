package com.jarvis.knowledge.extract;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Extracts previews from Markdown documents.
 */
@Component
public class MarkdownExtractor implements DocumentExtractor {

    /**
     * Checks whether this extractor supports an extension.
     *
     * @param extension file extension
     * @return true for Markdown
     */
    @Override
    public boolean supports(String extension) {
        return "md".equalsIgnoreCase(extension);
    }

    /**
     * Extracts a Markdown preview.
     *
     * @param path document path
     * @param maxLength maximum preview length
     * @return preview text
     */
    @Override
    public String preview(Path path, int maxLength) {
        try {
            return truncate(Files.readString(path, StandardCharsets.UTF_8), maxLength);
        } catch (IOException exception) {
            return "";
        }
    }

    private String truncate(String text, int maxLength) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }
}
