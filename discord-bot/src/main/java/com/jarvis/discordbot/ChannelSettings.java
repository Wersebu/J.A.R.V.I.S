package com.jarvis.discordbot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-channel reply probability, settable at runtime via {@code /trolbot randompickmessage} and
 * persisted to a small JSON file so the choice survives a restart. Any channel without an explicit
 * value falls back to the configured default.
 */
public final class ChannelSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelSettings.class);

    private final Path file;
    private final double defaultChance;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Double> chanceByChannel = new ConcurrentHashMap<>();

    public ChannelSettings(Path file, double defaultChance) {
        this.file = file;
        this.defaultChance = clamp(defaultChance);
        load();
    }

    /** Reply probability (0..1) for the given channel, or the default when none was set. */
    public double replyChance(String channelId) {
        return chanceByChannel.getOrDefault(channelId, defaultChance);
    }

    /** Sets and persists the reply probability for a channel. Returns the clamped value stored. */
    public double setReplyChance(String channelId, double value) {
        double clamped = clamp(value);
        chanceByChannel.put(channelId, clamped);
        save();
        return clamped;
    }

    private void load() {
        try {
            if (!Files.exists(file)) {
                return;
            }
            Map<String, Double> loaded = json.readValue(Files.readAllBytes(file),
                    new TypeReference<LinkedHashMap<String, Double>>() {
                    });
            loaded.forEach((channel, chance) -> {
                if (channel != null && chance != null) {
                    chanceByChannel.put(channel, clamp(chance));
                }
            });
            LOGGER.info("Loaded reply probabilities for {} channel(s) from {}", chanceByChannel.size(), file);
        } catch (IOException exception) {
            LOGGER.warn("Could not read {} ({}); starting with defaults.", file, exception.getMessage());
        }
    }

    private synchronized void save() {
        try {
            byte[] bytes = json.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(new LinkedHashMap<>(chanceByChannel));
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, bytes);
        } catch (IOException exception) {
            LOGGER.warn("Could not persist channel settings to {}: {}", file, exception.getMessage());
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
