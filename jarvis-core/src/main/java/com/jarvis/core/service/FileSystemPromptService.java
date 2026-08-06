package com.jarvis.core.service;

import com.jarvis.common.prompt.SystemPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Stores editable system instructions in the configured identity markdown file.
 */
@Service
public class FileSystemPromptService implements SystemPromptService {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSystemPromptService.class);

    private final Resource identityResource;

    /**
     * Creates the file-backed system prompt service.
     *
     * @param identityResource configured identity markdown resource
     */
    public FileSystemPromptService(@Value("${jarvis.ai.identity-file}") Resource identityResource) {
        this.identityResource = identityResource;
    }

    @Override
    public String load() {
        try {
            if (!identityPath().toFile().exists()) {
                return "";
            }
            return Files.readString(identityPath(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Failed to load system prompt from {}", identityResource, exception);
            throw new UncheckedIOException("Failed to load system prompt", exception);
        }
    }

    @Override
    public String save(String instructions) {
        String safeInstructions = instructions == null ? "" : instructions.strip();
        try {
            Path path = identityPath();
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, safeInstructions + System.lineSeparator(), StandardCharsets.UTF_8);
            LOGGER.info("[JARVIS] System prompt updated characters={}", safeInstructions.length());
            return safeInstructions;
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Failed to save system prompt to {}", identityResource, exception);
            throw new UncheckedIOException("Failed to save system prompt", exception);
        }
    }

    private Path identityPath() throws IOException {
        return identityResource.getFile().toPath().toAbsolutePath().normalize();
    }
}
