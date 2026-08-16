package com.jarvis.common.model;

/**
 * Thinking-budget mode for the Qwen thinking-budget control (currently applied only to an exact
 * configured target model, e.g. {@code qwen3.5:9b} - see {@code QwenThinkingBudgetProperties}).
 * Single source of truth for the available modes; the token cap per mode is configuration, not
 * hardcoded here, so it stays easy to retune without touching this enum.
 */
public enum QwenThinkingBudgetMode {
    /** Thinking completely disabled. */
    OFF,
    /** Small thinking budget for simple requests. */
    LOW,
    /** Default, moderate thinking budget. */
    NORMAL,
    /** Large thinking budget for harder requests. */
    HIGH,
    /** No additional cap beyond what the model/Ollama would do natively. */
    MAX
}
