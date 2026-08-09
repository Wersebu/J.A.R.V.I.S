package com.jarvis.core.workspace;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Configuration for temporary chat file workspaces.
 */
@Component
@ConfigurationProperties(prefix = "jarvis.workspace")
public class TemporaryWorkspaceProperties {

    private Path root = Path.of("temp-workspaces");
    private Duration ttl = Duration.ofMinutes(60);
    private Duration cleanupInterval = Duration.ofMinutes(5);
    private long maxFileSizeBytes = 2L * 1024L * 1024L;
    private long maxWorkspaceSizeBytes = 20L * 1024L * 1024L;
    private long maxTotalSizeBytes = 200L * 1024L * 1024L;
    private int maxFilesPerWorkspace = 20;
    private long minimumFreeDiskSpaceBytes = 512L * 1024L * 1024L;
    private int promptMaxCharacters = 60_000;

    /**
     * Returns workspace root.
     *
     * @return workspace root
     */
    public Path getRoot() {
        return root;
    }

    /**
     * Sets workspace root.
     *
     * @param root workspace root
     */
    public void setRoot(Path root) {
        this.root = root;
    }

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration getCleanupInterval() {
        return cleanupInterval;
    }

    public void setCleanupInterval(Duration cleanupInterval) {
        this.cleanupInterval = cleanupInterval;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public long getMaxWorkspaceSizeBytes() {
        return maxWorkspaceSizeBytes;
    }

    public void setMaxWorkspaceSizeBytes(long maxWorkspaceSizeBytes) {
        this.maxWorkspaceSizeBytes = maxWorkspaceSizeBytes;
    }

    public long getMaxTotalSizeBytes() {
        return maxTotalSizeBytes;
    }

    public void setMaxTotalSizeBytes(long maxTotalSizeBytes) {
        this.maxTotalSizeBytes = maxTotalSizeBytes;
    }

    public int getMaxFilesPerWorkspace() {
        return maxFilesPerWorkspace;
    }

    public void setMaxFilesPerWorkspace(int maxFilesPerWorkspace) {
        this.maxFilesPerWorkspace = maxFilesPerWorkspace;
    }

    public long getMinimumFreeDiskSpaceBytes() {
        return minimumFreeDiskSpaceBytes;
    }

    public void setMinimumFreeDiskSpaceBytes(long minimumFreeDiskSpaceBytes) {
        this.minimumFreeDiskSpaceBytes = minimumFreeDiskSpaceBytes;
    }

    public int getPromptMaxCharacters() {
        return promptMaxCharacters;
    }

    public void setPromptMaxCharacters(int promptMaxCharacters) {
        this.promptMaxCharacters = promptMaxCharacters;
    }
}
