package com.jarvis.brain.decision;

/**
 * Request complexity result.
 *
 * @param score score from 1 to 10
 * @param reason short explanation
 */
public record ComplexityScore(int score, String reason) {
}
