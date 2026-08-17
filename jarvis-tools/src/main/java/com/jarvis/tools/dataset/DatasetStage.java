package com.jarvis.tools.dataset;

/**
 * Machine-readable lifecycle stage of a {@link StoreAuditDataset}, so the model never has to
 * guess which stage of the workflow it is currently in.
 */
public enum DatasetStage {
    /** Incremental extraction in progress via START_DATASET/APPEND_RECORDS; record count not yet locked. */
    BUILDING,
    /** Extraction pass complete, record count locked; awaiting verification. */
    EXTRACTED,
    /** Verification pass complete and consistent with the extracted count. */
    LOCKED,
    /** Every record has been through at least one geolocation attempt. */
    GEOLOCATED,
    /** A day-by-day schedule was submitted and passed the count-invariant validation. */
    SCHEDULED
}
