package com.jarvis.api.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-to-service auth settings for TopkiMC moderation.
 */
@ConfigurationProperties(prefix = "jarvis.moderation")
public class TopkiMcModerationAuthProperties {

    private String apiKey = "";
    private int maxBodyBytes = 64_000;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }
}
