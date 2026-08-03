package com.jarvis.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Knowledge engine configuration.
 *
 * @param root knowledge root directory
 * @param watch whether file watching is enabled
 * @param previewLength maximum preview length
 */
@ConfigurationProperties(prefix = "knowledge")
public record KnowledgeProperties(String root, boolean watch, int previewLength) {

    /**
     * Creates knowledge configuration with safe defaults.
     */
    public KnowledgeProperties {
        root = root == null || root.isBlank() ? "./knowledge" : root;
        previewLength = previewLength > 0 ? previewLength : 500;
    }
}
