package com.jarvis.knowledge.extract;

import java.nio.file.Path;

/**
 * Extracts document preview text.
 */
public interface DocumentExtractor {

    /**
     * Checks whether this extractor supports an extension.
     *
     * @param extension file extension
     * @return true when supported
     */
    boolean supports(String extension);

    /**
     * Extracts a preview from a document.
     *
     * @param path document path
     * @param maxLength maximum preview length
     * @return extracted preview
     */
    String preview(Path path, int maxLength);
}
