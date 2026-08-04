package com.jarvis.brain.decision;

/**
 * Result of task type classification.
 *
 * @param taskType detected task type
 * @param confidence confidence from 0.0 to 1.0
 * @param reason short explanation
 */
public record TaskAnalysis(TaskType taskType, double confidence, String reason) {
}
