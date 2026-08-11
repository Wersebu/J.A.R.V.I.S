package com.jarvis.tools.runtime;

/**
 * Result of entity validation for a candidate source.
 *
 * @param accepted whether the candidate can be used
 * @param score matching score
 * @param reason diagnostic reason
 */
public record EntityMatchResult(boolean accepted, double score, String reason) {
}
