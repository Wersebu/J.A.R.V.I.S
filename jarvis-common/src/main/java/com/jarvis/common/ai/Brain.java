package com.jarvis.common.ai;

/**
 * Logical AI brain selected for a request.
 *
 * @param type logical brain type
 * @param provider provider identifier
 * @param model configured model name
 * @param description human-readable brain description
 * @param selectionReason reason why this brain was selected
 * @param routerLatencyMs router latency in milliseconds
 */
public record Brain(
        BrainType type,
        String provider,
        String model,
        String description,
        String selectionReason,
        long routerLatencyMs
) {

    /**
     * Creates a configured brain without routing metadata.
     *
     * @param type logical brain type
     * @param provider provider identifier
     * @param model configured model name
     * @param description human-readable brain description
     */
    public Brain(BrainType type, String provider, String model, String description) {
        this(type, provider, model, description, "", 0L);
    }

    /**
     * Returns this brain with routing metadata attached.
     *
     * @param reason selection reason
     * @param latencyMs router latency in milliseconds
     * @return brain with selection metadata
     */
    public Brain withRoutingMetadata(String reason, long latencyMs) {
        return new Brain(type, provider, model, description, reason, latencyMs);
    }
}
