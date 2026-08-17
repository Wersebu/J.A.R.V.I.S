package com.jarvis.tools.dataset;

import java.time.Instant;
import java.util.List;

/**
 * Canonical, conversation-scoped dataset of {@link StoreRecord}s for one Store Audit task.
 * Immutable - {@link StoreAuditDatasetService} replaces the stored instance wholesale on every
 * mutation, but every mutation preserves {@code expectedStoreCount}: records are only ever updated
 * in place, never appended or removed after {@link DatasetStage#EXTRACTED}, except through the
 * service's own explicit re-extraction path for a genuine user-supplied correction.
 *
 * <p>Kept reachable by {@code conversationId} (not just {@code datasetId}) so a later turn in the
 * same conversation can continue working against it without the user resending attachments - see
 * {@link StoreAuditDatasetService#findLatestForConversation}.</p>
 *
 * @param datasetId dataset id
 * @param requestId originating pipeline request id
 * @param conversationId owning conversation id, so a later turn can find this dataset without
 *         knowing its exact id - blank when the creating request had none registered
 * @param sourceAttachmentIds current-message attachment ids the records were extracted from
 * @param sourceImageCount number of image attachments read during extraction
 * @param stores canonical record list
 * @param expectedStoreCount record count captured at extraction time - must equal
 *         {@code stores.size()} for the lifetime of this dataset
 * @param stage machine-readable workflow stage
 * @param schedule validated day-by-day schedule, once {@link #stage} is {@link DatasetStage#SCHEDULED}
 * @param createdAt creation timestamp
 * @param expiresAt expiry timestamp, after which the dataset is swept
 */
public record StoreAuditDataset(
        String datasetId,
        String requestId,
        String conversationId,
        List<String> sourceAttachmentIds,
        int sourceImageCount,
        List<StoreRecord> stores,
        int expectedStoreCount,
        DatasetStage stage,
        List<ScheduleDay> schedule,
        Instant createdAt,
        Instant expiresAt
) {

    /**
     * Normalizes collection fields.
     */
    public StoreAuditDataset {
        conversationId = conversationId == null ? "" : conversationId;
        sourceAttachmentIds = sourceAttachmentIds == null ? List.of() : List.copyOf(sourceAttachmentIds);
        stores = stores == null ? List.of() : List.copyOf(stores);
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
    }

    /**
     * Returns a copy with a replaced record list and stage, preserving identity/provenance fields
     * and any previously submitted schedule.
     *
     * @param newStores replacement record list - must have the same size as the current list
     * @param newStage new workflow stage
     * @return updated dataset
     */
    public StoreAuditDataset withStores(List<StoreRecord> newStores, DatasetStage newStage) {
        return new StoreAuditDataset(datasetId, requestId, conversationId, sourceAttachmentIds, sourceImageCount,
                newStores, expectedStoreCount, newStage, schedule, createdAt, expiresAt);
    }

    /**
     * Returns a copy with a validated schedule applied and the stage advanced to
     * {@link DatasetStage#SCHEDULED}.
     *
     * @param newSchedule validated day-by-day schedule
     * @return updated dataset
     */
    public StoreAuditDataset withSchedule(List<ScheduleDay> newSchedule) {
        return new StoreAuditDataset(datasetId, requestId, conversationId, sourceAttachmentIds, sourceImageCount,
                stores, expectedStoreCount, DatasetStage.SCHEDULED, newSchedule, createdAt, expiresAt);
    }
}
