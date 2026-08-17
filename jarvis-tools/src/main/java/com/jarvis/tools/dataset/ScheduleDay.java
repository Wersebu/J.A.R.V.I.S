package com.jarvis.tools.dataset;

import java.util.List;

/**
 * One day of a proposed {@link StoreAuditDataset} schedule: a day number and the canonical
 * {@link StoreRecord} ids visited that day, in visit order.
 *
 * @param day day number (e.g. 1-based visit day within the planning period)
 * @param storeIds canonical record ids visited this day, in visit order
 */
public record ScheduleDay(int day, List<String> storeIds) {

    /**
     * Normalizes the store id list.
     */
    public ScheduleDay {
        storeIds = storeIds == null ? List.of() : List.copyOf(storeIds);
    }
}
