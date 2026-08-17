package com.jarvis.tools.dataset;

import java.time.Instant;
import java.util.List;

/**
 * Canonical, request-scoped dataset of {@link StoreRecord}s for one Store Audit task. Immutable -
 * {@link StoreAuditDatasetService} replaces the stored instance wholesale on every mutation, but
 * every mutation preserves {@code expectedStoreCount}: records are only ever updated in place,
 * never appended or removed after {@link DatasetStage#EXTRACTED}, except through the service's own
 * explicit re-extraction path for a genuine user-supplied correction.
 *
 * @param datasetId dataset id
 * @param requestId originating pipeline request id
 * @param sourceAttachmentIds current-message attachment ids the records were extracted from
 * @param sourceImageCount number of image attachments read during extraction
 * @param stores canonical record list
 * @param expectedStoreCount record count captured at extraction time - must equal
 *         {@code stores.size()} for the lifetime of this dataset
 * @param stage machine-readable workflow stage
 * @param createdAt creation timestamp
 * @param expiresAt expiry timestamp, after which the dataset is swept
 */
public record StoreAuditDataset(
        String datasetId,
        String requestId,
        List<String> sourceAttachmentIds,
        int sourceImageCount,
        List<StoreRecord> stores,
        int expectedStoreCount,
        DatasetStage stage,
        Instant createdAt,
        Instant expiresAt
) {

    /**
     * Normalizes collection fields.
     */
    public StoreAuditDataset {
        sourceAttachmentIds = sourceAttachmentIds == null ? List.of() : List.copyOf(sourceAttachmentIds);
        stores = stores == null ? List.of() : List.copyOf(stores);
    }

    /**
     * Returns a copy with a replaced record list and stage, preserving identity/provenance fields.
     *
     * @param newStores replacement record list - must have the same size as the current list
     * @param newStage new workflow stage
     * @return updated dataset
     */
    public StoreAuditDataset withStores(List<StoreRecord> newStores, DatasetStage newStage) {
        return new StoreAuditDataset(datasetId, requestId, sourceAttachmentIds, sourceImageCount,
                newStores, expectedStoreCount, newStage, createdAt, expiresAt);
    }
}
