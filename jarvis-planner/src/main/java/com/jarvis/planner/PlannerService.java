package com.jarvis.planner;

/**
 * Contract for future planning engines.
 */
public interface PlannerService {

    /**
     * Creates a plan for a task.
     *
     * @param task task to plan
     * @return planning result
     */
    PlannerResult plan(PlannerTask task);
}
