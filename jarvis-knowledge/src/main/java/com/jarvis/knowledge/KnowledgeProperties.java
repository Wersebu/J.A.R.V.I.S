package com.jarvis.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knowledge engine configuration.
 *
 * @param root knowledge root directory
 * @param watch whether file watching is enabled
 * @param previewLength maximum preview length
 * @param watcherDebounceMs debounce window for filesystem events
 */
@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(String root, boolean watch, int previewLength, int watcherDebounceMs) {

    /**
     * Creates knowledge configuration with safe defaults.
     */
    public KnowledgeProperties {
        root = root == null || root.isBlank() ? "./knowledge" : root;
        previewLength = previewLength > 0 ? previewLength : 500;
        watcherDebounceMs = watcherDebounceMs > 0 ? watcherDebounceMs : 300;
    }
}
