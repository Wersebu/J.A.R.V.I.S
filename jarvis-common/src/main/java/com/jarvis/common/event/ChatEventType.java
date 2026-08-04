package com.jarvis.common.event;

/**
 * Event types emitted by the real-time J.A.R.V.I.S. chat pipeline.
 */
public enum ChatEventType {
    /**
     * Request has entered the backend.
     */
    REQUEST_RECEIVED,

    /**
     * Brain routing has started or completed.
     */
    BRAIN_ROUTING,

    /**
     * Prompt building has started or completed.
     */
    PROMPT_BUILDING,

    /**
     * The selected model is being prepared by the provider.
     */
    MODEL_LOADING,

    /**
     * The model is preparing an answer.
     */
    THINKING,

    /**
     * Token generation has started.
     */
    GENERATING,

    /**
     * A generated token is available.
     */
    TOKEN,

    /**
     * Generation has completed.
     */
    FINISHED,

    /**
     * Knowledge usage metadata is available after generation.
     */
    KNOWLEDGE_USAGE,

    /**
     * The backend is idle after completing a request.
     */
    IDLE,

    /**
     * An error occurred while processing the request.
     */
    ERROR
}
