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
    /** Records the user's resolved scheduling preferences (days, distribution) for this dataset. */
    SET_PREFERENCES,
    /** Marks the dataset as legitimately paused awaiting a real decision from the user right now. */
    REQUEST_USER_INPUT,
    /** Submits a day-by-day schedule for count-invariant validation against the locked dataset. */
    SUBMIT_SCHEDULE
}
