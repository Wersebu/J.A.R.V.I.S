package com.jarvis.tools.dataset;

/**
 * Machine-readable lifecycle stage of a {@link StoreAuditDataset}, so the model never has to
 * guess which stage of the workflow it is currently in.
 */
public enum DatasetStage {
    /** Extraction pass complete, record count locked; awaiting verification. */
    EXTRACTED,
    /** Verification pass complete and consistent with the extracted count. */
    LOCKED,
    /** Every record has been through at least one geolocation attempt. */
    GEOLOCATED
}
