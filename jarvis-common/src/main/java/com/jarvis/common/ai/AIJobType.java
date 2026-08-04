package com.jarvis.common.ai;

/**
 * Type of model job submitted to an AI provider.
 */
public enum AIJobType {
    /** Interactive user chat. */
    CHAT,
    /** Background memory agent analysis. */
    MEMORY_AGENT,
    /** Background non-interactive work. */
    BACKGROUND,
    /** Debug or diagnostics request. */
    DEBUG
}
