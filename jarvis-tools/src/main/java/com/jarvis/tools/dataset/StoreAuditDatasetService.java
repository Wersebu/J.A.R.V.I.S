package com.jarvis.tools.dataset;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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

    // The Store Audit business (Nowa Wola start point, audited chains) is Poland-based, so "today"
    // for past-date/current-year scheduling validation is always evaluated in Poland's local
    // calendar date, regardless of the server's own timezone - a plan submitted late at night UTC
    // must never be rejected/accepted based on the wrong calendar day.
    private static final ZoneId SCHEDULING_ZONE = ZoneId.of("Europe/Warsaw");

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

    /**
     * Creates the dataset service with an explicit {@link Clock} - used by tests needing
     * deterministic "now" for TTL expiry and scheduling-date validation, and available for any
     * caller needing the same.
     *
     * @param cognitiveEventBus event bus for dataset lifecycle diagnostics
     * @param clock clock used for TTL expiry and scheduling-date validation
     */
    public StoreAuditDatasetService(CognitiveEventBus cognitiveEventBus, Clock clock) {
        this.cognitiveEventBus = cognitiveEventBus;
        this.clock = clock;
    }

    private record AttachmentRegistration(List<String> attachmentIds, String conversationId, Instant expiresAt) {
    }

    /**
     * Registers the real current-message attachment ids Core knows about for a request, so
     * {@link #createDataset} can cross-check the model's declared source attachments against
     * ground truth instead of trusting the model's self-report alone. Equivalent to calling
     * {@link #registerAttachments(String, String, List)} with a blank conversation id.
     *
     * @param requestId pipeline request id
     * @param attachmentIds real attachment ids resolved by Core for this request's current message
     */
    public void registerAttachments(String requestId, List<String> attachmentIds) {
        registerAttachments(requestId, "", attachmentIds);
    }

    /**
     * Registers the real current-message attachment ids Core knows about for a request, so
     * {@link #createDataset} can cross-check the model's declared source attachments against
     * ground truth instead of trusting the model's self-report alone. Also records which
     * conversation this request belongs to, so any dataset created for this request can later be
     * found again in a following turn via {@link #findLatestForConversation}.
     *
     * @param requestId pipeline request id
     * @param conversationId owning conversation id, blank if unknown
     * @param attachmentIds real attachment ids resolved by Core for this request's current message
     */
    public void registerAttachments(String requestId, String conversationId, List<String> attachmentIds) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        sweepExpired();
        attachmentsByRequest.put(requestId, new AttachmentRegistration(
                attachmentIds == null ? List.of() : List.copyOf(attachmentIds),
                conversationId == null ? "" : conversationId,
                clock.instant().plus(ATTACHMENT_REGISTRY_TTL)));
    }

    /**
     * Creates and locks a new canonical dataset from extracted candidate records, in one call.
     * Best for a modest record count; for a large extraction (roughly more than ~10 records) a
     * single native tool call asking the model to populate one huge array argument has been
     * observed to fail outright (the model emits an empty {@code records} array despite intending
     * to fill it) - {@link #startDataset}/{@link #appendRecords}/{@link #finalizeDataset} let the
     * model build the same dataset across several smaller calls instead.
     *
     * @param requestId pipeline request id
     * @param sourceImageCount number of image attachments read during extraction
     * @param expectedRecordCount total number of source records the model expects to extract
     *         (e.g. counted across every screenshot before submitting), 0 if not declared - checked
     *         against the accepted record count so an extraction can never silently finalize short
     * @param declaredAttachmentIds attachment ids the model claims it extracted records from - only
     *         used as a sanity check and as a fallback when Core has no real registration for this
     *         request; the authoritative set is the one registered via {@link #registerAttachments}
     * @param candidates extracted candidate records
     * @return outcome describing what was accepted, rejected, or deduplicated
     */
    public CreateOutcome createDataset(
            String requestId,
            int sourceImageCount,
            int expectedRecordCount,
            List<String> declaredAttachmentIds,
            List<CandidateRecord> candidates
    ) {
        return buildDataset(requestId, sourceImageCount, expectedRecordCount, declaredAttachmentIds, candidates, DatasetStage.EXTRACTED);
    }

    /**
     * Starts a new incremental dataset build with the first batch of extracted candidate records,
     * left in {@link DatasetStage#BUILDING} (record count not yet locked). Use {@link
     * #appendRecords} for further batches and {@link #finalizeDataset} once extraction is
     * complete. Subject to the same provenance and duplicate-source checks as {@link
     * #createDataset} - starting a second incremental build (or a one-shot {@link #createDataset})
     * for the same conversation and source attachments is rejected the same way.
     *
     * @param requestId pipeline request id
     * @param sourceImageCount number of image attachments read during extraction
     * @param expectedRecordCount total number of source records the model expects to extract
     *         across every batch it will submit, 0 if not declared
     * @param declaredAttachmentIds attachment ids the model claims it extracted records from - only
     *         used as a sanity check and as a fallback when Core has no real registration for this
     *         request; the authoritative set is the one registered via {@link #registerAttachments}
     * @param candidates first batch of extracted candidate records
     * @return outcome describing what was accepted, rejected, or deduplicated
     */
    public CreateOutcome startDataset(
            String requestId,
            int sourceImageCount,
            int expectedRecordCount,
            List<String> declaredAttachmentIds,
            List<CandidateRecord> candidates
    ) {
        return buildDataset(requestId, sourceImageCount, expectedRecordCount, declaredAttachmentIds, candidates, DatasetStage.BUILDING);
    }

    private CreateOutcome buildDataset(
            String requestId,
            int sourceImageCount,
            int expectedRecordCount,
            List<String> declaredAttachmentIds,
            List<CandidateRecord> candidates,
            DatasetStage targetStage
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
                return new CreateOutcome(false, null, 0, 0, List.of(), message, "STORE_DATASET_PROVENANCE_INVALID");
            }
        }

        // The dataset's authoritative attachment set is the real one Core registered for this
        // request (ToolCallingStage#registerAttachments, from the actual current-message
        // attachments) - never merely whatever subset the model happened to declare. This is what
        // lets every attachment's records be accepted from the start, instead of a later
        // APPEND_RECORDS batch for a second/third attachment being rejected wholesale just because
        // an earlier START_DATASET call only declared the first one. The model's own declared list
        // above is still checked for inventing an id that isn't real, but never narrows the legal
        // set below the real one. Falls back to the model-declared list only when Core has no
        // registration for this request at all (e.g. text-input mode, or a test that never calls
        // registerAttachments).
        List<String> realAttachmentIds = known.map(AttachmentRegistration::attachmentIds).orElse(List.of());
        List<String> effectiveAttachmentIds = !realAttachmentIds.isEmpty() ? realAttachmentIds : declared;

        String conversationIdForRequest = known.map(AttachmentRegistration::conversationId).orElse("");
        if (!effectiveAttachmentIds.isEmpty() && !conversationIdForRequest.isBlank()) {
            Set<String> declaredForDuplicateCheck = new HashSet<>(effectiveAttachmentIds);
            Optional<StoreAuditDataset> duplicate = datasets.values().stream()
                    .filter(existing -> conversationIdForRequest.equals(existing.conversationId()))
                    .filter(existing -> new HashSet<>(existing.sourceAttachmentIds()).equals(declaredForDuplicateCheck))
                    .findFirst();
            if (duplicate.isPresent()) {
                StoreAuditDataset existingDataset = duplicate.get();
                // A model that hits this rejection has been observed to not reliably follow the "call
                // GET_DATASET/VERIFY_DATASET instead" instruction below and just keep retrying
                // START_DATASET/CREATE_DATASET (both share this same duplicate check) with the same
                // attachments, going nowhere. When it genuinely submitted new candidate records this
                // call and the existing dataset can still accept them (stage=BUILDING), silently
                // absorb them via the same path APPEND_RECORDS uses instead of forcing the model to
                // self-correct - real extracted data must never be dropped just because it arrived
                // through the wrong entry point.
                if (!source.isEmpty() && existingDataset.stage() == DatasetStage.BUILDING) {
                    LOGGER.info("[STORE_AUDIT] requestId={} redirecting duplicate CREATE_DATASET/START_DATASET call "
                            + "for existing datasetId={} into APPEND_RECORDS ({} candidate(s))",
                            requestId, existingDataset.datasetId(), source.size());
                    AppendOutcome appended = appendRecords(existingDataset.datasetId(), source);
                    String prefix = "A dataset already existed for these exact source attachments (datasetId="
                            + existingDataset.datasetId() + ") - the submitted record(s) were appended to it "
                            + "instead of creating a duplicate. ";
                    return new CreateOutcome(appended.success(), appended.dataset(), appended.acceptedCount(),
                            appended.duplicateCount(), appended.rejected(), prefix + appended.message(), appended.errorCode());
                }
                String message = "A dataset already exists for these exact source attachments (datasetId="
                        + existingDataset.datasetId() + ", stage=" + existingDataset.stage() + ", "
                        + existingDataset.stores().size() + " record(s)). Do not create a duplicate dataset for "
                        + "the same source material - call storeDataset.GET_DATASET or storeDataset.VERIFY_DATASET "
                        + "with that id instead.";
                LOGGER.warn("[STORE_AUDIT] requestId={} extraction rejected: duplicate CREATE_DATASET for existing datasetId={}",
                        requestId, existingDataset.datasetId());
                return new CreateOutcome(false, existingDataset, 0, 0, List.of(), message, "STORE_DATASET_DUPLICATE_SOURCE");
            }
        }

        // sourceAttachmentIndex is resolved against the REAL registered attachment list, never the
        // model-declared one (which can be an incomplete/wrong subset) - a candidate referencing an
        // index outside 1..realAttachmentIds.size() rejects the WHOLE call outright with a precise
        // errorCode, rather than silently dropping just that one candidate: an out-of-range index is
        // a structural model error (a value that cannot possibly be right), not a plausible
        // low-quality read like a blurry address, so it gets the same "fix and resubmit" treatment
        // as an invalid declared attachment id above instead of a soft per-candidate reject.
        Optional<CreateOutcome> invalidIndex = validateAttachmentIndices(requestId, source, realAttachmentIds);
        if (invalidIndex.isPresent()) {
            return invalidIndex.get();
        }

        Set<String> allowedAttachmentSet = new HashSet<>(effectiveAttachmentIds);
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
            String sourceAttachmentId = resolveSourceAttachmentId(candidate, realAttachmentIds, effectiveAttachmentIds);
            if (!allowedAttachmentSet.isEmpty()) {
                if (sourceAttachmentId.isBlank() || !allowedAttachmentSet.contains(sourceAttachmentId)) {
                    rejected.add(new RejectedCandidate(index,
                            "Missing or invalid source provenance - sourceAttachmentId must be one of the current "
                                    + "message's real attachment ids"));
                    continue;
                }
            }
            // In text-input mode (no attachments known at all) a blank sourceAttachmentId is
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

        boolean building = targetStage == DatasetStage.BUILDING;
        // A one-shot CREATE_DATASET producing 0 records is always an error - there is no later
        // stage to add records at. START_DATASET is different: it is legal for the first batch to
        // land empty (e.g. every candidate in it failed provenance) as long as the model can still
        // recover via APPEND_RECORDS - only FINALIZE_DATASET rejects a dataset that is still empty
        // once the model has finished submitting batches.
        if (accepted.isEmpty() && !building) {
            String message = "Dataset creation rejected: the resulting record count would be 0 ("
                    + rejected.size() + " candidate(s) rejected for missing/invalid provenance, "
                    + duplicateCount + " duplicate(s) skipped). Re-read the currently available images/attachments "
                    + "or user-typed list and call CREATE_DATASET again with at least one valid record - or, for a "
                    + "large extraction, use START_DATASET with a smaller first batch.";
            LOGGER.warn("[STORE_AUDIT] requestId={} extraction rejected: CREATE_DATASET would produce an empty dataset "
                            + "(submitted={}, rejected={}, duplicates={})",
                    requestId, source.size(), rejected.size(), duplicateCount);
            return new CreateOutcome(false, null, 0, duplicateCount, rejected, message, "EMPTY_DATASET");
        }

        // CREATE_DATASET has no later batch to complete the extraction with, so its own declared
        // expectedRecordCount (if any) is checked immediately, the same way FINALIZE_DATASET checks
        // it for an incremental BUILDING->EXTRACTED transition - see completeExtractionInvariants.
        if (!building) {
            Optional<CreateOutcome> incomplete = incompleteExtractionRejection(
                    requestId, expectedRecordCount, accepted, effectiveAttachmentIds, "CREATE_DATASET");
            if (incomplete.isPresent()) {
                return incomplete.get();
            }
        }

        Instant now = clock.instant();
        String conversationId = conversationIdForRequest;
        StoreAuditDataset dataset = new StoreAuditDataset(
                UUID.randomUUID().toString(), requestId, conversationId, effectiveAttachmentIds, sourceImageCount,
                Math.max(expectedRecordCount, 0), accepted, accepted.size(), targetStage, List.of(), null,
                WorkflowPause.NONE, now, now.plus(DATASET_TTL));
        datasets.put(dataset.datasetId(), dataset);

        LOGGER.info("[STORE_AUDIT] requestId={} extraction pass1 count={} rejected={} duplicates={} stage={}",
                requestId, accepted.size(), rejected.size(), duplicateCount, targetStage);
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_CREATED, targetStage.name(),
                "Store Audit dataset created", dataset.datasetId(), Map.of(
                        "datasetId", dataset.datasetId(),
                        "requestId", requestId,
                        "count", accepted.size(),
                        "rejected", rejected.size(),
                        "duplicates", duplicateCount,
                        "sourceImageCount", sourceImageCount
                ));

        String message = (building
                ? "Dataset started with " + accepted.size() + " store record(s) so far (not yet finalized)."
                : "Dataset created with " + accepted.size() + " store record(s).")
                + (rejected.isEmpty() ? "" : " " + rejected.size() + " candidate(s) rejected for missing/invalid provenance.")
                + (duplicateCount > 0 ? " " + duplicateCount + " duplicate(s) skipped." : "")
                + (building ? " Call storeDataset.APPEND_RECORDS with the next batch, then storeDataset.FINALIZE_DATASET "
                + "once every record has been submitted." : "");
        return new CreateOutcome(true, dataset, accepted.size(), duplicateCount, rejected, message, "");
    }

    /**
     * Enforces the two code-level, generic (never hardcoded to a specific count) safety nets that
     * catch an incomplete extraction before it is allowed to lock in as {@link
     * DatasetStage#EXTRACTED}:
     * <ol>
     *     <li>if the model declared {@code expectedRecordCount} up front, the accepted count must
     *     match it exactly - the exact production bug this exists for is a 23-record extraction
     *     silently finalizing with only 14 records because a later batch's provenance was rejected;</li>
     *     <li>every real current-message attachment this dataset is scoped to must have contributed
     *     at least one record - a completely unrepresented attachment (e.g. a whole second
     *     screenshot's rows all rejected or never submitted) is caught even when the model never
     *     declared an expected count at all.</li>
     * </ol>
     *
     * @param requestId pipeline request id, for logging
     * @param expectedRecordCount model-declared expected total, 0/negative if not declared
     * @param accepted records accepted so far
     * @param attachmentIds the dataset's real attachment set
     * @param operationName {@code CREATE_DATASET} or {@code FINALIZE_DATASET}, for the message
     * @return a rejection outcome, if either invariant is violated; empty when the extraction may proceed
     */
    private Optional<CreateOutcome> incompleteExtractionRejection(
            String requestId,
            int expectedRecordCount,
            List<StoreRecord> accepted,
            List<String> attachmentIds,
            String operationName
    ) {
        if (expectedRecordCount > 0 && accepted.size() != expectedRecordCount) {
            int missing = expectedRecordCount - accepted.size();
            String message = "Dataset " + (missing > 0 ? "incomplete" : "rejected") + ": expected " + expectedRecordCount
                    + " source record(s) (as declared), but only " + accepted.size() + " were accepted"
                    + (missing > 0 ? " (" + missing + " missing)" : " (" + (-missing) + " more than expected)") + ". "
                    + "Re-check the source material for rows that were skipped or rejected, then "
                    + (operationName.equals("FINALIZE_DATASET")
                            ? "call storeDataset.APPEND_RECORDS with the missing records before finalizing again"
                            : "call CREATE_DATASET again with the complete, correct record list")
                    + " - never finalize short of the declared expected count.";
            LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} expectedRecords={} actualRecords={} missing={}",
                    requestId, expectedRecordCount, accepted.size(), Math.max(missing, 0));
            return Optional.of(new CreateOutcome(false, null, accepted.size(), 0, List.of(), message, "STORE_DATASET_INCOMPLETE_EXTRACTION"));
        }
        if (!attachmentIds.isEmpty()) {
            Set<String> representedAttachmentIds = new LinkedHashSet<>();
            for (StoreRecord record : accepted) {
                representedAttachmentIds.add(record.sourceAttachmentId());
            }
            List<String> unrepresented = attachmentIds.stream().filter(id -> !representedAttachmentIds.contains(id)).toList();
            if (!unrepresented.isEmpty()) {
                String message = "Dataset incomplete: attachment id(s) " + unrepresented + " contributed zero records - "
                        + "every current-message attachment used for this dataset must have at least one record. "
                        + "Re-read the missing attachment(s) and "
                        + (operationName.equals("FINALIZE_DATASET")
                                ? "call storeDataset.APPEND_RECORDS with their records before finalizing again"
                                : "call CREATE_DATASET again including their records")
                        + ".";
                LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} unrepresentedAttachmentIds={}", requestId, unrepresented);
                return Optional.of(new CreateOutcome(false, null, accepted.size(), 0, List.of(), message, "STORE_DATASET_ATTACHMENT_NOT_REPRESENTED"));
            }
        }
        return Optional.empty();
    }

    /**
     * Validates every candidate's {@link CandidateRecord#sourceAttachmentIndex()} (when supplied)
     * against the real current-message attachment list, deterministically - the model is never
     * responsible for knowing or copying the real attachment UUID; it only ever names a 1-based
     * position ("Image 1", "Image 2", ...) and Core resolves that itself. An index outside {@code
     * 1..realAttachmentIds.size()} can never be silently mapped, guessed, or resolved against a
     * previous message/turn's attachments - current-message provenance only.
     *
     * @param requestId pipeline request id, for logging
     * @param source candidate records to validate
     * @param realAttachmentIds Core's real, ordered current-message attachment ids
     * @return a rejection outcome naming the exact invalid index/valid range, if any candidate is
     *         out of range; empty when every supplied index (if any) is valid
     */
    private Optional<CreateOutcome> validateAttachmentIndices(
            String requestId,
            List<CandidateRecord> source,
            List<String> realAttachmentIds
    ) {
        List<Integer> invalid = source.stream()
                .map(CandidateRecord::sourceAttachmentIndex)
                .filter(Objects::nonNull)
                .filter(position -> position < 1 || position > realAttachmentIds.size())
                .distinct()
                .sorted()
                .toList();
        if (invalid.isEmpty()) {
            return Optional.empty();
        }
        String message = "Dataset creation rejected: sourceAttachmentIndex " + invalid + " is out of range - "
                + "this message has " + realAttachmentIds.size() + " current-message attachment(s), so valid "
                + "indices are " + (realAttachmentIds.isEmpty() ? "none (no attachments on this message)"
                        : "1.." + realAttachmentIds.size()) + ". Never guess an index and never reuse an "
                + "attachment from a previous message or earlier turn - only this message's own attachments "
                + "are valid provenance. Re-check which image each record actually came from and resubmit.";
        LOGGER.warn("[STORE_AUDIT] requestId={} extraction rejected: invalid sourceAttachmentIndex value(s)={} (valid range 1..{})",
                requestId, invalid, realAttachmentIds.size());
        return Optional.of(new CreateOutcome(false, null, 0, 0, List.of(), message, "STORE_DATASET_ATTACHMENT_INDEX_INVALID"));
    }

    /**
     * Resolves a candidate's real {@code sourceAttachmentId}: deterministically from {@link
     * CandidateRecord#sourceAttachmentIndex()} against Core's real attachment list when supplied
     * (the model is never trusted to also supply the correct real id itself in that case - the
     * index is Core-owned, not merely a hint), otherwise falls back to whatever {@link
     * CandidateRecord#sourceAttachmentId()} string the caller provided directly (explicit typed-list
     * input, or a caller that already resolved the real id itself) - UNLESS {@code
     * allowedAttachmentIds} is itself empty, in which case that fallback value is never trusted
     * either (see the {@code allowedAttachmentIds.isEmpty()} branch below).
     *
     * @param candidate candidate record
     * @param realAttachmentIds Core's real, ordered current-message attachment ids - used only for
     *         index-based resolution, which must never fall back to a merely-declared list (an index
     *         references a real attachment position, not a model claim)
     * @param allowedAttachmentIds the caller's own effective allowed-id set for this call (Core's
     *         real attachments when registered, otherwise the model-declared list - exactly what the
     *         caller itself validates {@code sourceAttachmentId} against right after this call
     *         returns) - when this is genuinely empty, no attachment of any kind is known for this
     *         call, so a non-blank {@code candidate.sourceAttachmentId()} could only be fabricated
     * @return resolved real attachment id, blank when neither an index nor an id was supplied, or
     *         when no attachment is known for this call at all
     */
    private String resolveSourceAttachmentId(
            CandidateRecord candidate,
            List<String> realAttachmentIds,
            List<String> allowedAttachmentIds
    ) {
        Integer position = candidate.sourceAttachmentIndex();
        if (position != null) {
            // Range already validated by validateAttachmentIndices before this is ever called -
            // this bound check is just defensive, never expected to actually trigger.
            if (position >= 1 && position <= realAttachmentIds.size()) {
                return realAttachmentIds.get(position - 1);
            }
            return "";
        }
        if (allowedAttachmentIds.isEmpty()) {
            // Text-only mode: no attachment (real or even merely declared) is known for this call at
            // all, so any non-blank value the model supplied here can only be fabricated - there is
            // nothing real or declared it could correctly be echoing back. Never store it. This
            // record's provenance is the user-typed text itself, represented as a blank
            // sourceAttachmentId exactly like every other text-only-mode record (the caller's own
            // allowedAttachmentSet.isEmpty() branch immediately after this call accepts that blank
            // value), never a model-invented id that would read back as if it pointed at a real
            // attachment that was never actually provided or declared.
            return "";
        }
        return safe(candidate.sourceAttachmentId());
    }

    /**
     * Appends another batch of extracted candidate records to a dataset still in {@link
     * DatasetStage#BUILDING} - the record count is not locked until {@link #finalizeDataset}.
     * Subject to the same per-record provenance checks as {@link #createDataset}, using the source
     * attachment ids declared when the dataset was started; duplicates are detected across every
     * batch appended so far, not just within this one.
     *
     * @param datasetId dataset id, from {@link #startDataset}
     * @param candidates next batch of extracted candidate records
     * @return outcome describing what was accepted, rejected, or deduplicated
     */
    public AppendOutcome appendRecords(String datasetId, List<CandidateRecord> candidates) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new AppendOutcome(false, null, 0, 0, List.of(), "Unknown or expired dataset id: " + datasetId,
                    "STORE_DATASET_NOT_FOUND");
        }
        if (dataset.stage() != DatasetStage.BUILDING) {
            String message = "Dataset " + datasetId + " is already finalized (stage=" + dataset.stage() + ") - "
                    + "APPEND_RECORDS only works on a dataset still being built. Start a new dataset with "
                    + "storeDataset.START_DATASET if this is genuinely new source material, or use "
                    + "storeDataset.VERIFY_DATASET to correct an already-finalized one.";
            return new AppendOutcome(false, dataset, 0, 0, List.of(), message, "STORE_DATASET_NOT_BUILDING");
        }

        List<CandidateRecord> source = candidates == null ? List.of() : candidates;
        List<String> declared = dataset.sourceAttachmentIds();
        Optional<CreateOutcome> invalidIndex = validateAttachmentIndices(dataset.requestId(), source, declared);
        if (invalidIndex.isPresent()) {
            CreateOutcome rejection = invalidIndex.get();
            return new AppendOutcome(false, dataset, 0, 0, List.of(), rejection.message(), rejection.errorCode());
        }
        Set<String> declaredSet = new HashSet<>(declared);
        Set<String> seenSourceKeys = new LinkedHashSet<>();
        for (StoreRecord existing : dataset.stores()) {
            seenSourceKeys.add(existing.sourceAttachmentId() + "::" + existing.sourceRow());
        }
        List<StoreRecord> accepted = new ArrayList<>();
        List<RejectedCandidate> rejected = new ArrayList<>();
        int duplicateCount = 0;
        int sequence = dataset.stores().size();

        for (int index = 0; index < source.size(); index++) {
            CandidateRecord candidate = source.get(index);
            String fullAddress = safe(candidate.fullAddress());
            if (fullAddress.isBlank()) {
                rejected.add(new RejectedCandidate(index, "Missing fullAddress"));
                continue;
            }
            String sourceAttachmentId = resolveSourceAttachmentId(candidate, declared, declared);
            if (!declared.isEmpty()) {
                if (sourceAttachmentId.isBlank() || !declaredSet.contains(sourceAttachmentId)) {
                    rejected.add(new RejectedCandidate(index,
                            "Missing or invalid source provenance - sourceAttachmentId must be one of the dataset's "
                                    + "declared current-message attachment ids"));
                    continue;
                }
            }
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

        if (accepted.isEmpty()) {
            String message = "Append rejected: no valid new records in this batch (" + rejected.size()
                    + " candidate(s) rejected for missing/invalid provenance, " + duplicateCount + " already in the "
                    + "dataset). Supply at least one new, valid record, or call storeDataset.FINALIZE_DATASET if "
                    + "every record has already been submitted.";
            return new AppendOutcome(false, dataset, 0, duplicateCount, rejected, message, "EMPTY_APPEND");
        }

        List<StoreRecord> updatedStores = new ArrayList<>(dataset.stores());
        updatedStores.addAll(accepted);
        StoreAuditDataset updated = dataset.withAppendedStores(updatedStores);
        datasets.put(datasetId, updated);

        LOGGER.info("[STORE_AUDIT] requestId={} datasetId={} append accepted={} rejected={} duplicates={} totalNow={}",
                dataset.requestId(), datasetId, accepted.size(), rejected.size(), duplicateCount, updated.stores().size());
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_UPDATED, "BUILDING",
                "Store Audit dataset records appended", datasetId, Map.of(
                        "datasetId", datasetId,
                        "requestId", dataset.requestId(),
                        "appended", accepted.size(),
                        "count", updated.stores().size()
                ));

        String message = "Appended " + accepted.size() + " record(s); dataset now has " + updated.stores().size()
                + " record(s) total (not yet finalized)."
                + (rejected.isEmpty() ? "" : " " + rejected.size() + " candidate(s) rejected for missing/invalid provenance.")
                + (duplicateCount > 0 ? " " + duplicateCount + " duplicate(s) skipped." : "");
        return new AppendOutcome(true, updated, accepted.size(), duplicateCount, rejected, message, "");
    }

    /**
     * Locks the record count of a dataset still in {@link DatasetStage#BUILDING}, advancing it to
     * {@link DatasetStage#EXTRACTED} - from this point on it behaves exactly like a dataset created
     * in one call via {@link #createDataset}. Rejects finalizing an empty dataset (nothing was ever
     * successfully appended) the same way {@link #createDataset} rejects an empty submission.
     * Calling this again on an already-finalized dataset is a safe no-op, not an error.
     *
     * @param datasetId dataset id
     * @return outcome describing the finalized dataset, or why it could not be finalized
     */
    public FinalizeOutcome finalizeDataset(String datasetId) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new FinalizeOutcome(false, null, "Unknown or expired dataset id: " + datasetId, "STORE_DATASET_NOT_FOUND");
        }
        if (dataset.stage() != DatasetStage.BUILDING) {
            return new FinalizeOutcome(true, dataset,
                    "Dataset " + datasetId + " was already finalized (stage=" + dataset.stage() + ", "
                            + dataset.stores().size() + " record(s)).", "");
        }
        if (dataset.stores().isEmpty()) {
            String message = "Finalize rejected: dataset has 0 records. Call storeDataset.APPEND_RECORDS with at "
                    + "least one valid record before finalizing.";
            LOGGER.warn("[STORE_AUDIT] requestId={} datasetId={} finalize rejected: 0 records", dataset.requestId(), datasetId);
            return new FinalizeOutcome(false, dataset, message, "EMPTY_DATASET");
        }
        Optional<CreateOutcome> incomplete = incompleteExtractionRejection(
                dataset.requestId(), dataset.expectedRecordCount(), dataset.stores(), dataset.sourceAttachmentIds(), "FINALIZE_DATASET");
        if (incomplete.isPresent()) {
            return new FinalizeOutcome(false, dataset, incomplete.get().message(), incomplete.get().errorCode());
        }
        StoreAuditDataset finalized = dataset.withStage(DatasetStage.EXTRACTED);
        datasets.put(datasetId, finalized);
        LOGGER.info("[STORE_AUDIT] requestId={} datasetId={} finalized count={}",
                dataset.requestId(), datasetId, finalized.stores().size());
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_CREATED, "EXTRACTED",
                "Store Audit dataset finalized", datasetId, Map.of(
                        "datasetId", datasetId,
                        "requestId", dataset.requestId(),
                        "count", finalized.stores().size()
                ));
        String message = "Dataset finalized with " + finalized.stores().size() + " record(s). Record count is now locked.";
        return new FinalizeOutcome(true, finalized, message, "");
    }

    /**
     * Applies a verification pass to an existing dataset. Requires FULL coverage - every canonical
     * record id referenced in the pass exactly once, no record missing, none duplicated, none
     * hallucinated - mirroring the invariant {@link #submitSchedule} enforces on a proposed
     * schedule. Rejects the whole pass, without applying anything or changing the stage, otherwise:
     * a single-record verification pass against a 23-record dataset must never be accepted as if it
     * verified the whole thing.
     *
     * @param datasetId dataset id
     * @param verifications verification entries, referencing existing records by id
     * @return outcome, including whether an invariant violation was detected
     */
    public VerifyOutcome verifyDataset(String datasetId, List<VerificationEntry> verifications) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new VerifyOutcome(false, null, false, List.of(), List.of(), List.of(), "Unknown or expired dataset id: " + datasetId);
        }
        if (dataset.stage() == DatasetStage.BUILDING) {
            return new VerifyOutcome(false, dataset, false, List.of(), List.of(), List.of(),
                    "Dataset " + datasetId + " is still being built (stage=BUILDING, " + dataset.stores().size()
                            + " record(s) so far). Call storeDataset.FINALIZE_DATASET before verifying it.");
        }
        List<VerificationEntry> entries = verifications == null ? List.of() : verifications;
        Set<String> knownIds = new LinkedHashSet<>();
        for (StoreRecord record : dataset.stores()) {
            knownIds.add(record.id());
        }

        List<String> unknownIds = new ArrayList<>();
        Set<String> seenUnknown = new LinkedHashSet<>();
        Set<String> seenKnown = new HashSet<>();
        List<String> duplicateIds = new ArrayList<>();
        for (VerificationEntry entry : entries) {
            String id = entry.recordId();
            if (id == null || !knownIds.contains(id)) {
                if (id != null && seenUnknown.add(id)) {
                    unknownIds.add(id);
                }
                continue;
            }
            if (!seenKnown.add(id)) {
                duplicateIds.add(id);
            }
        }
        List<String> missingIds = new ArrayList<>();
        for (String id : knownIds) {
            if (!seenKnown.contains(id)) {
                missingIds.add(id);
            }
        }

        boolean valid = unknownIds.isEmpty() && duplicateIds.isEmpty() && missingIds.isEmpty();
        LOGGER.info("[STORE_AUDIT_VALIDATION] requestId={} datasetId={} expected={} actual={} missing={} duplicates={} unknown={} valid={}",
                dataset.requestId(), datasetId, knownIds.size(), seenKnown.size(),
                missingIds.size(), duplicateIds.size(), unknownIds.size(), valid);
        if (!valid) {
            LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} datasetId={} stage=VERIFICATION expected={} actual={} missing={} unknown={} duplicates={}",
                    dataset.requestId(), datasetId, knownIds.size(), entries.size(), missingIds, unknownIds, duplicateIds);
            cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_INVARIANT_VIOLATION, "VERIFICATION", "Store Audit dataset invariant violated",
                    datasetId, Map.of(
                            "datasetId", datasetId,
                            "requestId", dataset.requestId(),
                            "expected", knownIds.size(),
                            "actual", entries.size(),
                            "missingRecordIds", missingIds,
                            "duplicateRecordIds", duplicateIds,
                            "unknownRecordIds", unknownIds
                    ));
            String message = "Verification pass rejected: expected exactly " + knownIds.size()
                    + " record(s) (the locked dataset size), each verified exactly once."
                    + (missingIds.isEmpty() ? "" : " Missing from this pass: " + missingIds + ".")
                    + (unknownIds.isEmpty() ? "" : " Unknown/hallucinated id(s): " + unknownIds + ".")
                    + (duplicateIds.isEmpty() ? "" : " Duplicated id(s): " + duplicateIds + ".")
                    + " Call GET_DATASET and resubmit a corrected verification pass covering every record exactly once.";
            return new VerifyOutcome(false, dataset, true, missingIds, duplicateIds, unknownIds, message);
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

        return new VerifyOutcome(true, locked, false, List.of(), List.of(), List.of(),
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
     * Finds the most recently created, non-expired dataset for a conversation - so a later turn
     * ("polacz dzien 3 i 4") can continue working against the same canonical records without the
     * user resending attachments. Never returns a dataset created for a different conversation, and
     * never matches on a blank conversation id (which would otherwise match every dataset created
     * without one, e.g. in tests that never call {@link #registerAttachments}).
     *
     * @param conversationId owning conversation id
     * @return the latest dataset for that conversation, if any
     */
    public Optional<StoreAuditDataset> findLatestForConversation(String conversationId) {
        sweepExpired();
        if (conversationId == null || conversationId.isBlank()) {
            return Optional.empty();
        }
        return datasets.values().stream()
                .filter(dataset -> conversationId.equals(dataset.conversationId()))
                .max(Comparator.comparing(StoreAuditDataset::createdAt));
    }

    /**
     * Applies geolocation results to existing records by id. Can never create or remove a record -
     * entries referencing an unknown record id are reported, not applied. Only legal once the
     * dataset has been through a full verification pass ({@link DatasetStage#LOCKED}) - or is
     * already {@link DatasetStage#GEOLOCATED} (a legitimate retry of unresolved records) - so a
     * model can never skip straight from a bare extraction to geolocation without ever verifying
     * the extracted addresses.
     *
     * @param datasetId dataset id
     * @param results geolocation results, referencing existing records by id
     * @return outcome describing what was updated, or why geolocation could not proceed
     */
    public GeolocationUpdateOutcome updateGeolocation(String datasetId, List<GeolocationEntry> results) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new GeolocationUpdateOutcome(false, null, 0, List.of(), "Unknown or expired dataset id: " + datasetId, "STORE_DATASET_NOT_FOUND");
        }
        if (dataset.stage() == DatasetStage.BUILDING) {
            return new GeolocationUpdateOutcome(false, dataset, 0, List.of(),
                    "Dataset " + datasetId + " is still being built (stage=BUILDING, " + dataset.stores().size()
                            + " record(s) so far). Call storeDataset.FINALIZE_DATASET before geocoding it.",
                    "STORE_DATASET_NOT_BUILDING");
        }
        if (dataset.stage() != DatasetStage.LOCKED && dataset.stage() != DatasetStage.GEOLOCATED) {
            String message = dataset.stage() == DatasetStage.SCHEDULED
                    ? "Dataset " + datasetId + " already has an accepted schedule (stage=SCHEDULED) - geolocation is "
                            + "no longer applicable."
                    : "Dataset " + datasetId + " has not been verified yet (stage=" + dataset.stage() + "). "
                            + "Call storeDataset.VERIFY_DATASET before geolocation.";
            LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} datasetId={} stage=GEOLOCATION attempted before verification (stage={})",
                    dataset.requestId(), datasetId, dataset.stage());
            return new GeolocationUpdateOutcome(false, dataset, 0, List.of(), message, "STORE_DATASET_NOT_VERIFIED");
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
        return new GeolocationUpdateOutcome(true, newDataset, updatedCount, unknownIds, message, "");
    }

    /**
     * Records the user's resolved scheduling preferences (which days, how the audit days are
     * spread across the month) against the dataset, so this decision is a typed, persisted fact
     * the rest of the workflow references - never left only in the model's own free-text reasoning
     * between the question and the final schedule. Clears any pending pause ({@link
     * #requestUserInput}) - calling this is how the model reports the user actually answered.
     *
     * <p>{@code preferences.year()} defaults to the current year (evaluated in Poland's local
     * calendar, see {@link #SCHEDULING_ZONE}) when not a positive value - callers must never guess
     * or hardcode a year themselves. The resulting year/month must not already be entirely in the
     * past.</p>
     *
     * @param datasetId dataset id
     * @param preferences resolved preferences - {@code month} must be 1-12 and at least one
     *         preferred or fallback day of week must be set
     * @return outcome describing the accepted preferences, or why they were rejected
     */
    public PreferencesOutcome setPreferences(String datasetId, SchedulingPreferences preferences) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new PreferencesOutcome(false, null, "Unknown or expired dataset id: " + datasetId, "STORE_DATASET_NOT_FOUND");
        }
        if (dataset.stage() == DatasetStage.BUILDING) {
            return new PreferencesOutcome(false, dataset, "Dataset " + datasetId + " is still being built (stage=BUILDING). "
                    + "Call storeDataset.FINALIZE_DATASET before setting scheduling preferences.", "STORE_DATASET_NOT_BUILDING");
        }
        if (preferences == null || preferences.month() < 1 || preferences.month() > 12) {
            return new PreferencesOutcome(false, dataset,
                    "Rejected: month must be 1-12 (got " + (preferences == null ? "none" : preferences.month()) + ").",
                    "STORE_AUDIT_PREFERENCES_INVALID_MONTH");
        }
        LocalDate today = LocalDate.now(clock.withZone(SCHEDULING_ZONE));
        int year = preferences.year() > 0 ? preferences.year() : today.getYear();
        YearMonth target = YearMonth.of(year, preferences.month());
        if (target.isBefore(YearMonth.from(today))) {
            String message = "Rejected: " + target + " has already passed (today is " + today + "). Ask the user "
                    + "whether to use a different month, or plan within whatever days remain in the current month.";
            return new PreferencesOutcome(false, dataset, message, "STORE_AUDIT_PREFERENCES_MONTH_IN_PAST");
        }
        if (preferences.allowedDaysOfWeek().isEmpty()) {
            return new PreferencesOutcome(false, dataset,
                    "Rejected: at least one preferred or fallback day of week must be set.",
                    "STORE_AUDIT_PREFERENCES_NO_DAYS");
        }
        SchedulingPreferences resolved = new SchedulingPreferences(year, preferences.month(), preferences.preferredDaysOfWeek(),
                preferences.fallbackDaysOfWeek(), preferences.strategy(), preferences.explicitStartDate(),
                preferences.explicitEndDate(), preferences.saturdayExplicitlyAllowed());
        StoreAuditDataset updated = dataset.withPreferences(resolved);
        datasets.put(datasetId, updated);
        LOGGER.info("[STORE_AUDIT] requestId={} datasetId={} preferences set year={} month={} strategy={} preferredDays={} fallbackDays={} saturday={}",
                dataset.requestId(), datasetId, year, preferences.month(), resolved.strategy(),
                resolved.preferredDaysOfWeek(), resolved.fallbackDaysOfWeek(), resolved.saturdayExplicitlyAllowed());
        String message = "Scheduling preferences set: " + target + ", strategy=" + resolved.strategy()
                + ", preferred days=" + resolved.preferredDaysOfWeek() + ", fallback days=" + resolved.fallbackDaysOfWeek()
                + (resolved.saturdayExplicitlyAllowed() ? ", Saturday explicitly allowed" : "") + ".";
        return new PreferencesOutcome(true, updated, message, "");
    }

    /**
     * Marks the dataset as legitimately paused awaiting a real decision from the user right now -
     * e.g. a borderline daily-limit tradeoff the workflow document says to ask about - so {@link
     * com.jarvis.tools.workflow.StoreAuditWorkflowCompletionValidator} lets this turn end with a
     * genuine question instead of forcing more tool calls. Cleared automatically by any subsequent
     * real mutation (verification, geolocation, preferences, schedule submission) - never survives
     * genuine forward progress.
     *
     * @param datasetId dataset id
     * @param pause the kind of pause to record - {@link WorkflowPause#NONE} is rejected, use a real
     *         reason
     * @param reason short human-readable reason, for traceability
     * @return outcome describing the recorded pause, or why it could not be recorded
     */
    public PauseOutcome requestUserInput(String datasetId, WorkflowPause pause, String reason) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new PauseOutcome(false, null, "Unknown or expired dataset id: " + datasetId, "STORE_DATASET_NOT_FOUND");
        }
        if (pause == null || pause == WorkflowPause.NONE) {
            return new PauseOutcome(false, dataset, "Rejected: a real pause reason (AWAITING_PREFERENCES or "
                    + "AWAITING_DECISION) is required.", "STORE_AUDIT_PAUSE_INVALID");
        }
        if (dataset.stage() == DatasetStage.BUILDING) {
            return new PauseOutcome(false, dataset, "Dataset " + datasetId + " is still being built (stage=BUILDING). "
                    + "Finish extraction before pausing for a scheduling decision.", "STORE_DATASET_NOT_BUILDING");
        }
        StoreAuditDataset updated = dataset.withPendingUserInput(pause);
        datasets.put(datasetId, updated);
        LOGGER.info("[STORE_AUDIT] requestId={} datasetId={} paused kind={} reason={}",
                dataset.requestId(), datasetId, pause, reason);
        return new PauseOutcome(true, updated, "Recorded pause: " + pause + ". Ask the user the question directly "
                + "in this turn's final response, then continue once they answer.", "");
    }

    /**
     * Validates and applies a proposed day-by-day schedule against the locked dataset: every
     * canonical record id must appear in exactly one day - no missing store, no duplicate, no
     * unknown/hallucinated id - every day must have a real calendar date within the agreed planning
     * window, no date in the past, no empty day, and no negative distance/duration. Rejects the
     * whole submission (applying nothing) otherwise, exactly the count-invariant guarantee this
     * mechanism exists to enforce - a locked 23-record dataset can never silently turn into a "22 of
     * 23" or "24 of 23" schedule presented as valid.
     *
     * @param datasetId dataset id
     * @param days proposed day-by-day grouping
     * @return outcome describing success, or exactly which ids are missing/unknown/duplicated
     */
    public ScheduleSubmitOutcome submitSchedule(String datasetId, List<ScheduleDay> days) {
        sweepExpired();
        StoreAuditDataset dataset = datasets.get(datasetId);
        if (dataset == null) {
            return new ScheduleSubmitOutcome(false, null, false, List.of(), List.of(), List.of(),
                    "Unknown or expired dataset id: " + datasetId);
        }
        if (dataset.stage() == DatasetStage.BUILDING) {
            return new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(),
                    "Dataset " + datasetId + " is still being built (stage=BUILDING, " + dataset.stores().size()
                            + " record(s) so far). Call storeDataset.FINALIZE_DATASET before submitting a schedule.");
        }
        if (dataset.preferences() == null) {
            return new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(),
                    "Dataset " + datasetId + " has no scheduling preferences yet. Call storeDataset.SET_PREFERENCES "
                            + "with the user's agreed days/distribution before submitting a schedule.");
        }
        List<ScheduleDay> proposedDays = days == null ? List.of() : days;
        Optional<ScheduleSubmitOutcome> dateViolation = validateScheduleDates(dataset, proposedDays);
        if (dateViolation.isPresent()) {
            return dateViolation.get();
        }
        Set<String> knownIds = new LinkedHashSet<>();
        for (StoreRecord record : dataset.stores()) {
            knownIds.add(record.id());
        }

        List<String> flatIds = new ArrayList<>();
        for (ScheduleDay day : proposedDays) {
            flatIds.addAll(day.storeIds());
        }

        List<String> unknownIds = new ArrayList<>();
        Set<String> seenUnknown = new LinkedHashSet<>();
        Set<String> seenKnown = new HashSet<>();
        List<String> duplicateIds = new ArrayList<>();
        for (String id : flatIds) {
            if (!knownIds.contains(id)) {
                if (seenUnknown.add(id)) {
                    unknownIds.add(id);
                }
                continue;
            }
            if (!seenKnown.add(id)) {
                duplicateIds.add(id);
            }
        }
        List<String> missingIds = new ArrayList<>();
        for (String id : knownIds) {
            if (!seenKnown.contains(id)) {
                missingIds.add(id);
            }
        }

        boolean valid = unknownIds.isEmpty() && duplicateIds.isEmpty() && missingIds.isEmpty();
        LOGGER.info("[STORE_AUDIT_VALIDATION] requestId={} datasetId={} datasetStores={} scheduledUniqueStores={} duplicates={} missing={} unknown={} valid={}",
                dataset.requestId(), datasetId, dataset.expectedStoreCount(), seenKnown.size(),
                duplicateIds.size(), missingIds.size(), unknownIds.size(), valid);

        if (!valid) {
            LOGGER.warn("[STORE_AUDIT][INVARIANT_VIOLATION] requestId={} datasetId={} stage=SCHEDULE missing={} unknown={} duplicates={}",
                    dataset.requestId(), datasetId, missingIds, unknownIds, duplicateIds);
            cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_INVARIANT_VIOLATION, "SCHEDULE",
                    "Store Audit schedule invariant violated", datasetId, Map.of(
                            "datasetId", datasetId,
                            "requestId", dataset.requestId(),
                            "expected", dataset.expectedStoreCount(),
                            "missingStoreIds", missingIds,
                            "unknownStoreIds", unknownIds,
                            "duplicateStoreIds", duplicateIds
                    ));
            String message = "Schedule rejected: expected exactly " + dataset.expectedStoreCount()
                    + " unique store id(s), each exactly once."
                    + (missingIds.isEmpty() ? "" : " Missing from schedule: " + missingIds + ".")
                    + (unknownIds.isEmpty() ? "" : " Unknown/hallucinated id(s): " + unknownIds + ".")
                    + (duplicateIds.isEmpty() ? "" : " Duplicated id(s): " + duplicateIds + ".")
                    + " Call GET_DATASET and resubmit a corrected schedule referencing only existing record ids, each exactly once.";
            return new ScheduleSubmitOutcome(false, dataset, true, missingIds, unknownIds, duplicateIds, message);
        }

        StoreAuditDataset scheduled = dataset.withSchedule(proposedDays);
        datasets.put(datasetId, scheduled);
        cognitiveEventBus.publish(CognitiveEventType.WORKFLOW_DATASET_UPDATED, DatasetStage.SCHEDULED.name(),
                "Store Audit schedule validated and applied", datasetId, Map.of(
                        "datasetId", datasetId,
                        "requestId", dataset.requestId(),
                        "days", proposedDays.size(),
                        "count", dataset.stores().size()
                ));
        return new ScheduleSubmitOutcome(true, scheduled, false, List.of(), List.of(), List.of(),
                "Schedule accepted: " + proposedDays.size() + " day(s), " + seenKnown.size() + " store(s), each exactly once.");
    }

    /**
     * Validates every day's real calendar date, before the record-id invariants are even checked:
     * present, within the agreed planning window (the explicit range if the user gave one,
     * otherwise the preferences' year/month), not in the past, the day itself not empty, and no
     * negative distance/duration - each a hard, code-level check so the model's own arithmetic can
     * never silently produce an impossible or out-of-window plan.
     *
     * @param dataset the dataset being scheduled - {@link StoreAuditDataset#preferences()} must
     *         already be non-null (checked by the caller)
     * @param proposedDays proposed day-by-day grouping
     * @return a rejection outcome for the first violation found; empty when every day is valid
     */
    private Optional<ScheduleSubmitOutcome> validateScheduleDates(StoreAuditDataset dataset, List<ScheduleDay> proposedDays) {
        SchedulingPreferences preferences = dataset.preferences();
        LocalDate today = LocalDate.now(clock.withZone(SCHEDULING_ZONE));
        LocalDate windowStart = preferences.hasExplicitRange() ? preferences.explicitStartDate() : null;
        LocalDate windowEnd = preferences.hasExplicitRange() ? preferences.explicitEndDate() : null;
        YearMonth targetMonth = YearMonth.of(preferences.year(), preferences.month());
        for (ScheduleDay day : proposedDays) {
            if (day.storeIds().isEmpty()) {
                String message = "Schedule rejected: day " + day.day() + " has no stores assigned to it. Remove "
                        + "empty days entirely rather than submitting them.";
                return Optional.of(new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(), message));
            }
            if (day.date() == null) {
                String message = "Schedule rejected: day " + day.day() + " has no date. Every scheduled day must "
                        + "carry a real calendar date - compute it, never omit it.";
                return Optional.of(new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(), message));
            }
            if (day.date().isBefore(today)) {
                String message = "Schedule rejected: day " + day.day() + " is dated " + day.date() + ", which is "
                        + "before today (" + today + "). Store Audit visits can never be scheduled in the past.";
                return Optional.of(new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(), message));
            }
            boolean withinWindow = windowStart != null
                    ? !day.date().isBefore(windowStart) && !day.date().isAfter(windowEnd)
                    : YearMonth.from(day.date()).equals(targetMonth);
            if (!withinWindow) {
                String expected = windowStart != null ? windowStart + " .. " + windowEnd : targetMonth.toString();
                String message = "Schedule rejected: day " + day.day() + " is dated " + day.date() + " ("
                        + day.date().getDayOfWeek() + "), which falls outside the agreed planning window (" + expected
                        + "). Call storeDataset.SET_PREFERENCES again if the window genuinely needs to change, "
                        + "otherwise keep every date inside it.";
                return Optional.of(new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(), message));
            }
            DayOfWeek dayOfWeek = day.date().getDayOfWeek();
            if (!preferences.allowedDaysOfWeek().contains(dayOfWeek)) {
                String message = "Schedule rejected: day " + day.day() + " is dated " + day.date() + ", a " + dayOfWeek
                        + " - not one of the agreed days (" + preferences.allowedDaysOfWeek() + "). Only use a day "
                        + "of week outside that set if the user explicitly agreed to it via SET_PREFERENCES.";
                return Optional.of(new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(), message));
            }
            if (day.routeDistanceMeters() < 0 || day.routeDurationSeconds() < 0 || day.auditDurationSeconds() < 0) {
                String message = "Schedule rejected: day " + day.day() + " has a negative distance/duration value - "
                        + "routeDistanceMeters, routeDurationSeconds, and auditDurationSeconds must all be zero or "
                        + "greater.";
                return Optional.of(new ScheduleSubmitOutcome(false, dataset, false, List.of(), List.of(), List.of(), message));
            }
        }
        return Optional.empty();
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
