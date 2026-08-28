package com.jarvis.core.moderation;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Runtime settings for the isolated TopkiMC moderation pipeline.
 */
@ConfigurationProperties(prefix = "jarvis.moderation")
public class TopkiMcModerationProperties {

    private boolean enabled = false;
    private String model = "";
    private Duration timeout = Duration.ofSeconds(180);
    private String policyVersion = "v1";
    private int maxBodyBytes = 64_000;
    private int maxTextChars = 20_000;
    private int maxTitleChars = 160;
    private int maxUrls = 50;
    private int maxYoutubeVideoIds = 20;
    private int maxParallel = 2;
    private int maxQueue = 8;
    private Duration queueTimeout = Duration.ofSeconds(2);
    private int requestsPerMinute = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout == null ? Duration.ofSeconds(180) : timeout;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion == null || policyVersion.isBlank() ? "v1" : policyVersion;
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = maxBodyBytes;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public int getMaxTitleChars() {
        return maxTitleChars;
    }

    public void setMaxTitleChars(int maxTitleChars) {
        this.maxTitleChars = maxTitleChars;
    }

    public int getMaxUrls() {
        return maxUrls;
    }

    public void setMaxUrls(int maxUrls) {
        this.maxUrls = maxUrls;
    }

    public int getMaxYoutubeVideoIds() {
        return maxYoutubeVideoIds;
    }

    public void setMaxYoutubeVideoIds(int maxYoutubeVideoIds) {
        this.maxYoutubeVideoIds = maxYoutubeVideoIds;
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = maxParallel;
    }

    public int getMaxQueue() {
        return maxQueue;
    }

    public void setMaxQueue(int maxQueue) {
        this.maxQueue = maxQueue;
    }

    public Duration getQueueTimeout() {
        return queueTimeout;
    }

    public void setQueueTimeout(Duration queueTimeout) {
        this.queueTimeout = queueTimeout == null ? Duration.ofSeconds(2) : queueTimeout;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }
}
