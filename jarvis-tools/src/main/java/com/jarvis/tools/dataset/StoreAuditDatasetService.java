package com.jarvis.tools.dataset;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds one canonical, request-scoped {@link StoreAuditDataset} per Store Audit task and enforces
 * its invariants: the record count never changes after extraction except through an explicit
 * user-supplied correction, every record has current-message attachment provenance, and neither
 * verification nor geolocation can ever create or remove a record.
 *
 * <p>This is the mechanism that replaces the previous behavior of the model re-deriving the store
 * list from free-text reasoning at every tool-loop turn - once a dataset is created here, every
 * later stage references it by id instead of restating it, so it cannot silently grow, shrink or
 * drift as the task progresses.</p>
 */
@Service
public class StoreAuditDatasetService {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreAuditDatasetService.class);
    private static final Duration DATASET_TTL = Duration.ofHours(2);
    private static final Duration ATTACHMENT_REGISTRY_TTL = Duration.ofHours(2);

    private final Map<String, StoreAuditDataset> datasets = new ConcurrentHashMap<>();
    private final Map<String, AttachmentRegistration> attachmentsByRequest = new ConcurrentHashMap<>();
    private final CognitiveEventBus cognitiveEventBus;
    private final Clock clock;

    /**
     * Creates the dataset service.
     *
     * @param cognitiveEventBus event bus for dataset lifecycle diagnostics
     */
    @Autowired
    public StoreAuditDatasetService(CognitiveEventBus cognitiveEventBus) {
        this(cognitiveEventBus, Clock.systemUTC());
    }

    StoreAuditDatasetService(CognitiveEventBus cognitiveEventBus, Clock clock) {
        this.cognitiveEventBus = cognitiveEventBus;
        this.clock = clock;
    }

    private record AttachmentRegistration(List<String> attachmentIds, Instant expiresAt) {
    }

    /**
     * Registers the real current-message attachment ids Core knows about for a request, so
     * {@link #createDataset} can cross-check the model's declared source attachments against
     * ground truth instead of trusting the model's self-report alone.
     *
     * @param requestId pipeline request id
     * @param attachmentIds real attachment ids resolved by Core for this request's current message
     */
    public void registerAttachments(String requestId, List<String> attachmentIds) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        sweepExpired();
        attachmentsByRequest.put(requestId, new AttachmentRegistration(
                attachmentIds == null ? List.of() : List.copyOf(attachmentIds),
                clock.instant().plus(ATTACHMENT_REGISTRY_TTL)));
    }

    /**
     * Creates and locks a new canonical dataset from extracted candidate records.
     *
     * @param requestId pipeline request id
     * @param sourceImageCount number of image attachments read during extraction
     * @param declaredAttachmentIds attachment ids the model claims it extracted records from
     * @param candidates extracted candidate records
     * @return outcome describing what was accepted, rejected, or deduplicated
     */
    public CreateOutcome createDataset(
            String requestId,
            int sourceImageCount,
            List<String> declaredAttachmentIds,
            List<CandidateRecord> candidates
    ) {
        sweepExpired();
        List<String> declared = declaredAttachmentIds == null ? List.of() : List.copyOf(declaredAttachmentIds);
        List<CandidateRecord> source = candidates == null ? List.of() : candidates;

        Optional<AttachmentRegistration> known = Optional.ofNullable(attachmentsByRequest.get(requestId));
        if (known.isPresent() && !declared.isEmpty()) {
            Set<String> realIds = new HashSet<>(known.get().attachmentIds());
            List<String> unknownDeclared = declared.stream().filter(id -> !realIds.contains(id)).toList();
            if (!unknownDeclared.isEmpty()) {
                String message = "sourceAttachmentIds " + unknownDeclared + " do not match any real attachment on "
                        + "the current message - dataset was not created. Only attachments actually present on this "
                        + "message are valid provenance.";
                LOGGER.warn("[STORE_AUDIT] requestId={} extraction rejected: unknown declared attachment ids={}", requestId, unknownDeclared);
                return new CreateOutcome(false, null, 0, 0, List.of(), message);
            }
        }

        Set<String> declaredSet = new HashSet<>(declared);
        Set<String> seenSourceKeys = new LinkedHashSet<>();
        List<StoreRecord> accepted = new ArrayList<>();
        List<RejectedCandidate> rejected = new ArrayList<>();
        int duplicateCount = 0;
        int sequence = 0;

        for (int index = 0; index < source.size(); index++) {
            CandidateRecord candidate = source.get(index);
            String fullAddress = safe(candidate.fullAddress());
            if (fullAddress.isBlank()) {
                rejected.add(new RejectedCandidate(index, "Missing fullAddress"));
                continue;
            }
            String sourceAttachmentId = safe(candidate.sourceAttachmentId());
            if (!declared.isEmpty()) {
                if (sourceAttachmentId.isBlank() || !declaredSet.contains(sourceAttachmentId)) {
                    rejected.add(new RejectedCandidate(index,
                            "Missing or invalid source provenance - sourceAttachmentId must be one of the declared "
                                    + "current-message attachment ids"));
                    continue;
                }
            }
            // In text-input mode (no attachments declared at all) a blank sourceAttachmentId is
            // accepted - the explicit user-typed list is itself the valid source in that case.

            String dedupeKey = sourceAttachmentId + "::" + candidate.sourceRow();
            if (!seenSourceKeys.add(dedupeKey)) {
                duplicateCount++;
                continue;
            }

            sequence++;
            accepted.add(new StoreRecord(
                    recordId(sequence), safe(candidate.network()), safe(candidate.city()), safe(candidate.street()),
                    safe(candidate.buildingNumber()), safe(candidate.postalCode()), fullAddress, sourceAttachmentId,
                    candidate.sourceRow(), VerificationStatus.UNVERIFIED, GeolocationStatus.PENDING, null, null));
        }

        Instant now = clock.instant();
        StoreAuditDataset dataset = new StoreAuditDataset(
                UUID.randomUUID().toString(), requestId, declared, sourceImageCount,
                accepted, accepted.size(), DatasetStage.EXTRACTED, now, now.plus(DATASET_TTL));
        datasets.put(dataset.datasetId(), dataset);

        LOGGER.info("[STORE_AUDIT] requestId={} extraction pass1 count={} rejected={} duplicates={}",
                requestId, accepted.size(), rejected.size(), duplicateCount);
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_CREATED, "EXTRACTED",
                "Store Audit dataset created", dataset.datasetId(), Map.of(
                        "datasetId", dataset.datasetId(),
                        "requestId", requestId,
                        "count", accepted.size(),
                        "rejected", rejected.size(),
                        "duplicates", duplicateCount,
                        "sourceImageCount", sourceImageCount
                ));

        String message = "Dataset created with " + accepted.size() + " store record(s)."
                + (rejected.isEmpty() ? "" : " " + rejected.size() + " candidate(s) rejected for missing/invalid provenance.")
                + (duplicateCount > 0 ? " " + duplicateCount + " duplicate(s) skipped." : "");
        return new CreateOutcome(true, dataset, accepted.size(), duplicateCount, rejected, message);
    }

    /**
     * Applies a verification pass to an existing dataset. Rejects the whole pass, without applying
     * anything, when it references unknown record ids or reports an implausible record count - the
     * exact failure mode this mechanism exists to prevent (e.g. a locked 23-record dataset getting
     * a 117-entry "verification").
     *
     * @param datasetId dataset id
     * @param verifications verification entries, referencing existing records by id
     * @return outcome, including whether an invariant violation was detected
     */
    public VerifyOutcome verifyDataset(String datasetId, List<VerificationEntry> verifications) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new VerifyOutcome(false, null, false, List.of(), "Unknown or expired dataset id: " + datasetId);
        }
        List<VerificationEntry> entries = verifications == null ? List.of() : verifications;
        Map<String, StoreRecord> byId = new LinkedHashMap<>();
        for (StoreRecord record : dataset.stores()) {
            byId.put(record.id(), record);
        }
        List<String> unknownIds = entries.stream()
                .map(VerificationEntry::recordId)
                .filter(id -> id == null || !byId.containsKey(id))
                .toList();
        boolean countImplausible = entries.size() > dataset.expectedStoreCount();
        if (!unknownIds.isEmpty() || countImplausible) {
            LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} datasetId={} expected={} actual={} stage=VERIFICATION unknownIds={}",
                    dataset.requestId(), datasetId, dataset.expectedStoreCount(), entries.size(), unknownIds);
            cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_INVARIANT_VIOLATION, "VERIFICATION", "Store Audit dataset invariant violated",
                    datasetId, Map.of(
                            "datasetId", datasetId,
                            "requestId", dataset.requestId(),
                            "expected", dataset.expectedStoreCount(),
                            "actual", entries.size(),
                            "unknownRecordIds", unknownIds
                    ));
            String message = "Verification pass rejected: expected around " + dataset.expectedStoreCount()
                    + " record(s) (the locked dataset size), but this pass reported " + entries.size() + " entries"
                    + (unknownIds.isEmpty() ? "" : " and referenced unknown record id(s) " + unknownIds)
                    + ". Call GET_DATASET and recheck against the existing records instead of regenerating a new list.";
            return new VerifyOutcome(false, dataset, true, unknownIds, message);
        }

        List<StoreRecord> updated = new ArrayList<>(dataset.stores());
        for (VerificationEntry entry : entries) {
            int position = indexOf(updated, entry.recordId());
            if (position < 0) {
                continue;
            }
            VerificationStatus status = "CORRECTED".equalsIgnoreCase(safe(entry.status()))
                    ? VerificationStatus.CORRECTED : VerificationStatus.VERIFIED;
            updated.set(position, updated.get(position).withVerification(status, entry.correctedFullAddress(), entry.correctedPostalCode()));
        }
        if (updated.size() != dataset.expectedStoreCount()) {
            throw new IllegalStateException("Dataset invariant violated internally: expected "
                    + dataset.expectedStoreCount() + " but has " + updated.size());
        }
        StoreAuditDataset locked = dataset.withStores(updated, DatasetStage.LOCKED);
        datasets.put(datasetId, locked);

        LOGGER.info("[STORE_AUDIT] requestId={} verification count={} dataset locked count={}",
                dataset.requestId(), entries.size(), locked.stores().size());
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_VERIFIED, "LOCKED", "Store Audit dataset verified and locked",
                datasetId, Map.of("datasetId", datasetId, "requestId", dataset.requestId(), "count", locked.stores().size()));

        return new VerifyOutcome(true, locked, false, List.of(),
                "Verification applied. Dataset locked with " + locked.stores().size() + " record(s).");
    }

    /**
     * Returns the current canonical dataset, if it exists and has not expired.
     *
     * @param datasetId dataset id
     * @return dataset, if present
     */
    public Optional<StoreAuditDataset> getDataset(String datasetId) {
        sweepExpired();
        return Optional.ofNullable(datasets.get(datasetId));
    }

    /**
     * Applies geolocation results to existing records by id. Can never create or remove a record -
     * entries referencing an unknown record id are reported, not applied.
     *
     * @param datasetId dataset id
     * @param results geolocation results, referencing existing records by id
     * @return outcome describing what was updated
     */
    public GeolocationUpdateOutcome updateGeolocation(String datasetId, List<GeolocationEntry> results) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new GeolocationUpdateOutcome(false, null, 0, List.of(), "Unknown or expired dataset id: " + datasetId);
        }
        List<GeolocationEntry> entries = results == null ? List.of() : results;
        List<StoreRecord> updated = new ArrayList<>(dataset.stores());
        List<String> unknownIds = new ArrayList<>();
        int updatedCount = 0;
        for (GeolocationEntry entry : entries) {
            int position = indexOf(updated, entry.recordId());
            if (position < 0) {
                unknownIds.add(entry.recordId());
                continue;
            }
            updated.set(position, updated.get(position).withGeolocation(entry.status(), entry.latitude(), entry.longitude()));
            updatedCount++;
        }
        if (updated.size() != dataset.expectedStoreCount()) {
            throw new IllegalStateException("Dataset invariant violated internally: expected "
                    + dataset.expectedStoreCount() + " but has " + updated.size());
        }
        boolean everyRecordAttempted = updated.stream().noneMatch(record -> record.geolocationStatus() == GeolocationStatus.PENDING);
        DatasetStage stage = everyRecordAttempted ? DatasetStage.GEOLOCATED : dataset.stage();
        StoreAuditDataset newDataset = dataset.withStores(updated, stage);
        datasets.put(datasetId, newDataset);

        long resolved = updated.stream().filter(record -> record.geolocationStatus() == GeolocationStatus.RESOLVED).count();
        long unresolved = updated.size() - resolved;
        LOGGER.info("[STORE_AUDIT] requestId={} geolocation requested={} success={} unresolved={}",
                dataset.requestId(), entries.size(), resolved, unresolved);
        LOGGER.info("[STORE_AUDIT] requestId={} dataset invariant count={} OK", dataset.requestId(), newDataset.stores().size());
        if (!unknownIds.isEmpty()) {
            LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} datasetId={} stage=GEOLOCATION unknownRecordIds={} (ignored, not applied)",
                    dataset.requestId(), datasetId, unknownIds);
        }
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_UPDATED, stage.name(), "Store Audit dataset geolocation updated",
                datasetId, Map.of(
                        "datasetId", datasetId,
                        "requestId", dataset.requestId(),
                        "updated", updatedCount,
                        "unknownRecordIds", unknownIds,
                        "count", newDataset.stores().size()
                ));

        String message = "Updated " + updatedCount + " record(s)."
                + (unknownIds.isEmpty() ? "" : " " + unknownIds.size() + " unknown record id(s) ignored (no record created).");
        return new GeolocationUpdateOutcome(true, newDataset, updatedCount, unknownIds, message);
    }

    private int indexOf(List<StoreRecord> records, String recordId) {
        if (recordId == null) {
            return -1;
        }
        for (int index = 0; index < records.size(); index++) {
            if (records.get(index).id().equals(recordId)) {
                return index;
            }
        }
        return -1;
    }

    private String recordId(int sequence) {
        return String.format(Locale.ROOT, "store-%03d", sequence);
    }

    private String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private void sweepExpired() {
        Instant now = clock.instant();
        datasets.values().removeIf(dataset -> now.isAfter(dataset.expiresAt()));
        attachmentsByRequest.values().removeIf(registration -> now.isAfter(registration.expiresAt()));
    }
}
