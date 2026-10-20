package com.jarvis.discordbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Holds the bot's current persona (the text pushed to Core as the user's global prompt) and
 * persists edits made from Discord to a file, so a restart keeps the edited personality instead of
 * reverting to the built-in default.
 */
public final class PersonaStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonaStore.class);

    private final Path file;
    private volatile String current;

    /**
     * @param file          where an edited persona is stored
     * @param builtInDefault persona used when no edited one has been saved yet
     */
    public PersonaStore(Path file, String builtInDefault) {
        this.file = file;
        this.current = loadOrDefault(builtInDefault);
    }

    public String current() {
        return current;
    }

    /** Replaces the persona and persists it. */
    public synchronized void update(String persona) {
        String normalized = persona == null ? "" : persona.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Persona must not be empty.");
        }
        this.current = normalized;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, normalized, StandardCharsets.UTF_8);
            LOGGER.info("Persona updated and saved to {} ({} chars).", file, normalized.length());
        } catch (IOException exception) {
            LOGGER.warn("Could not persist persona to {}: {}", file, exception.getMessage());
        }
    }

    private String loadOrDefault(String builtInDefault) {
        try {
            if (Files.exists(file)) {
                String saved = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!saved.isBlank()) {
                    LOGGER.info("Loaded saved persona from {} ({} chars).", file, saved.length());
                    return saved;
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not read saved persona {}: {}; using built-in.", file, exception.getMessage());
        }
        return builtInDefault;
    }
}
