package com.jarvis.tools.dataset;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * One day of a proposed {@link StoreAuditDataset} schedule: a calendar date, the canonical
 * {@link StoreRecord} ids visited that day, and the full closed-route cost of visiting them (start
 * point to first stop, between every stop, and back to the start point - never merely between
 * stops).
 *
 * @param day day number (1-based visit day within the planning period)
 * @param date the real calendar date this day is scheduled on - computed/chosen against a real
 *         calendar, never guessed; {@link #dayOfWeek()} is always derived from this, so the two can
 *         never disagree
 * @param storeIds canonical record ids visited this day, in visit order
 * @param routeDistanceMeters full closed-route distance for the day (start -&gt; every stop -&gt;
 *         start), in meters
 * @param routeDurationSeconds full closed-route travel duration for the day (start -&gt; every stop
 *         -&gt; start), in seconds - travel time only, excludes time spent auditing
 * @param auditDurationSeconds total estimated time spent auditing this day's stores, in seconds
 */
public record ScheduleDay(
        int day,
        LocalDate date,
        List<String> storeIds,
        double routeDistanceMeters,
        double routeDurationSeconds,
        double auditDurationSeconds
) {

    /**
     * Normalizes the store id list.
     */
    public ScheduleDay {
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }

    /**
     * The day of week this day falls on, derived from {@link #date} - never separately supplied,
     * so it can never drift from the actual calendar date.
     *
     * @return day of week, or null when {@link #date} is null
     */
    public DayOfWeek dayOfWeek() {
        return date == null ? null : date.getDayOfWeek();
    }

    /**
     * Total estimated time this day requires: travel (start -&gt; stops -&gt; start) plus time
     * spent auditing.
     *
     * @return total work time in seconds
     */
    public double totalWorkSeconds() {
        return routeDurationSeconds + auditDurationSeconds;
    }
}
