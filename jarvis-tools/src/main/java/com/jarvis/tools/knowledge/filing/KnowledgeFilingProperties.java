package com.jarvis.tools.knowledge.filing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for intelligent knowledge filing.
 */
@ConfigurationProperties(prefix = "jarvis.knowledge.filing")
public record KnowledgeFilingProperties(
        Boolean enabled,
        Boolean useAiPlanner,
        double categoryConfidenceThreshold,
        Boolean inboxFallbackEnabled,
        Boolean inspectTreeBeforeWrite,
        Boolean searchBeforeCreate,
        Boolean updateExistingDocument,
        Boolean rawMessageStorage
) {

    /**
     * Applies safe defaults.
     */
    public KnowledgeFilingProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        useAiPlanner = useAiPlanner == null ? Boolean.FALSE : useAiPlanner;
        categoryConfidenceThreshold = categoryConfidenceThreshold > 0 ? categoryConfidenceThreshold : 0.70d;
        inboxFallbackEnabled = inboxFallbackEnabled == null ? Boolean.TRUE : inboxFallbackEnabled;
        inspectTreeBeforeWrite = inspectTreeBeforeWrite == null ? Boolean.TRUE : inspectTreeBeforeWrite;
        searchBeforeCreate = searchBeforeCreate == null ? Boolean.TRUE : searchBeforeCreate;
        updateExistingDocument = updateExistingDocument == null ? Boolean.TRUE : updateExistingDocument;
        rawMessageStorage = rawMessageStorage == null ? Boolean.FALSE : rawMessageStorage;
    }

    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    public boolean allowInboxFallback() {
        return Boolean.TRUE.equals(inboxFallbackEnabled);
    }

    public boolean updateExisting() {
        return Boolean.TRUE.equals(updateExistingDocument);
    }

    public boolean storeRawMessages() {
        return Boolean.TRUE.equals(rawMessageStorage);
    }
}
