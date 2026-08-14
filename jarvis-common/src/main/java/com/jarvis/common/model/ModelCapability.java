package com.jarvis.common.model;

/**
 * A capability a local AI model may report support for. Capability support is always derived
 * from the model provider at runtime (e.g. Ollama's {@code /api/tags} response) - never assumed
 * from a model name - so new models gain capabilities automatically without code changes.
 */
public enum ModelCapability {
    /** Plain text generation. */
    TEXT,
    /** Accepts images alongside a prompt. */
    VISION,
    /** Supports native tool/function calling. */
    TOOLS,
    /** Supports a separate reasoning/thinking channel. */
    THINKING,
    /** Supports constrained JSON-formatted output. */
    JSON
}
