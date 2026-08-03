package com.jarvis.brain;

/**
 * Configurable logical brain definition.
 *
 * @param provider provider identifier
 * @param model provider model name
 * @param description human-readable description
 */
public record BrainDefinition(String provider, String model, String description) {
}
