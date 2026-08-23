package com.jarvis.tools.dataset;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The user's real, resolved decision about how to place a {@link StoreAuditDataset}'s audit days
 * within a month - captured once via {@link
 * StoreAuditDatasetService#setPreferences} and referenced by {@link
 * StoreAuditDatasetService#submitSchedule} from then on, so this decision never lives only in the
 * model's own free-text reasoning between the question and the final schedule.
 *
 * @param year planning year - always a concrete year, never left for the model to guess
 * @param month planning month, 1-12
 * @param preferredDaysOfWeek days that should be used first/most, e.g. Tuesday+Wednesday
 * @param fallbackDaysOfWeek days that may be used only when needed to produce a sensible schedule,
 *         e.g. Monday - distinct from {@code preferredDaysOfWeek} so a plan can report which
 *         category each chosen day actually came from
 * @param strategy how audit days should be spread across the month
 * @param explicitStartDate explicit user-supplied start of the planning window, or null when the
 *         whole month (per {@code strategy}) applies instead
 * @param explicitEndDate explicit user-supplied end of the planning window, or null when the whole
 *         month (per {@code strategy}) applies instead
 * @param saturdayExplicitlyAllowed true only when the user explicitly agreed to use Saturday -
 *         Saturday is never assumed available otherwise
 */
public record SchedulingPreferences(
        int year,
        int month,
        Set<DayOfWeek> preferredDaysOfWeek,
        Set<DayOfWeek> fallbackDaysOfWeek,
        DistributionStrategy strategy,
        LocalDate explicitStartDate,
        LocalDate explicitEndDate,
        boolean saturdayExplicitlyAllowed
) {

    /**
     * Normalizes collection/enum fields.
     */
    public SchedulingPreferences {
        preferredDaysOfWeek = preferredDaysOfWeek == null ? Set.of() : Set.copyOf(preferredDaysOfWeek);
        fallbackDaysOfWeek = fallbackDaysOfWeek == null ? Set.of() : Set.copyOf(fallbackDaysOfWeek);
        strategy = strategy == null ? DistributionStrategy.EVEN : strategy;
    }

    /**
     * Every day of week this dataset's schedule may legally use: preferred, fallback, and (only
     * when explicitly allowed) Saturday - never any other day.
     *
     * @return allowed days of week, in calendar order
     */
    public Set<DayOfWeek> allowedDaysOfWeek() {
        Set<DayOfWeek> allowed = new TreeSet<>(preferredDaysOfWeek);
        allowed.addAll(fallbackDaysOfWeek);
        if (saturdayExplicitlyAllowed) {
            allowed.add(DayOfWeek.SATURDAY);
        }
        return allowed;
    }

    /**
     * Whether an explicit date range overrides the month-wide {@link #strategy}.
     *
     * @return true when both {@link #explicitStartDate} and {@link #explicitEndDate} are set
     */
    public boolean hasExplicitRange() {
        return explicitStartDate != null && explicitEndDate != null;
    }

    /**
     * Convenience factory building the {@link DayOfWeek} sets from case-insensitive names (e.g.
     * from tool-call arguments), silently ignoring any value that is not a valid day name.
     *
     * @param year planning year
     * @param month planning month, 1-12
     * @param preferredDayNames preferred day-of-week names
     * @param fallbackDayNames fallback day-of-week names
     * @param strategy distribution strategy
     * @param explicitStartDate explicit start date, or null
     * @param explicitEndDate explicit end date, or null
     * @param saturdayExplicitlyAllowed whether Saturday was explicitly agreed to
     * @return resolved preferences
     */
    public static SchedulingPreferences of(
            int year,
            int month,
            List<String> preferredDayNames,
            List<String> fallbackDayNames,
            DistributionStrategy strategy,
            LocalDate explicitStartDate,
            LocalDate explicitEndDate,
            boolean saturdayExplicitlyAllowed
    ) {
        return new SchedulingPreferences(year, month, parseDays(preferredDayNames), parseDays(fallbackDayNames),
                strategy, explicitStartDate, explicitEndDate, saturdayExplicitlyAllowed);
    }

    private static Set<DayOfWeek> parseDays(List<String> names) {
        Set<DayOfWeek> days = new TreeSet<>();
        if (names == null) {
            return days;
        }
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                days.add(DayOfWeek.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Unrecognized day name - ignored rather than guessed; StoreAuditDatasetService
                // validates the resulting set is non-empty before accepting preferences.
            }
        }
        return days;
    }
}
