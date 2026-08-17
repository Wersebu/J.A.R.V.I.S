package com.jarvis.tools.dataset;

/**
 * Operations exposed by {@link StoreDatasetTool}.
 */
public enum StoreDatasetOperation {
    /** Submits the extracted record list once, locking the canonical count. */
    CREATE_DATASET,
    /** Starts an incremental dataset build with the first batch of records (stage=BUILDING). */
    START_DATASET,
    /** Appends another batch of records to a dataset still being built. */
    APPEND_RECORDS,
    /** Locks the record count of a dataset still being built, advancing it past BUILDING. */
    FINALIZE_DATASET,
    /** Submits a verification pass against the already-locked dataset. */
    VERIFY_DATASET,
    /** Reads the current canonical dataset. */
    GET_DATASET,
    /** Submits a day-by-day schedule for count-invariant validation against the locked dataset. */
    SUBMIT_SCHEDULE
}
