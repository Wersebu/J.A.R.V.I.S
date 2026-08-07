package com.jarvis.common.diagnostics;

/**
 * Primary bottleneck classification for one Ollama request.
 */
public enum OllamaBottleneckType {
    /** Model loading dominates the request. */
    MODEL_LOAD,
    /** Prompt evaluation dominates the request. */
    PROMPT_EVALUATION,
    /** Local request queue dominates the request. */
    QUEUE_WAIT,
    /** Token generation dominates the request. */
    GENERATION,
    /** Client-side transport or response header wait dominates the request. */
    TRANSPORT,
    /** Metrics are not sufficient to classify the bottleneck. */
    UNKNOWN
}
