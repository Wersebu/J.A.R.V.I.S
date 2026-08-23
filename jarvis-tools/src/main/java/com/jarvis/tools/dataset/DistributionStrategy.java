package com.jarvis.tools.dataset;

/**
 * How audit days should be spread across the planning period, as decided with the user (see
 * {@link SchedulingPreferences}) - never guessed or defaulted deep inside scheduling logic.
 */
public enum DistributionStrategy {
    /** Finish every audit within the first two weeks of the month. */
    BEGINNING,
    /** Finish every audit within the last two weeks of the month. */
    ENDING,
    /** Spread audit days as evenly as reasonably possible across every week of the month. */
    EVEN
}
