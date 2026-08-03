package com.jarvis.planner;

import java.util.List;

/**
 * Result produced by a future planning engine.
 *
 * @param accepted whether the task can be planned
 * @param steps planned execution steps
 */
public record PlannerResult(boolean accepted, List<String> steps) {
}
