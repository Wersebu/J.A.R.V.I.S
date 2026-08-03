package com.jarvis.planner;

/**
 * Task submitted to a future planning engine.
 *
 * @param id stable task identifier
 * @param goal requested goal
 */
public record PlannerTask(String id, String goal) {
}
