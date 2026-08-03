package com.jarvis.common.ai;

/**
 * Logical AI brain selected for a request.
 *
 * @param type logical brain type
 * @param provider provider identifier
 * @param model configured model name
 * @param description human-readable brain description
 */
public record Brain(BrainType type, String provider, String model, String description) {
}
