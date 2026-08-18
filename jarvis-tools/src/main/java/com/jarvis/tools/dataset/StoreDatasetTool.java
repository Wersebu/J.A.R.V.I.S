package com.jarvis.tools.dataset;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolJsonSchema;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Native tool that holds the canonical, structured dataset for multi-step extraction workflows
 * (currently Store Audit scheduling) - so the model references one stable, Core-validated record
 * list across every subsequent tool call instead of restating/reconstructing it from free-text
 * reasoning at every turn, which is what previously let a dataset drift or explode in size across
 * a long tool loop.
 *
 * <p>Deliberately named/shaped around "store" records for now (see {@link StoreRecord}) rather
 * than built as a fully generic framework - but every invariant here (provenance required, record
 * count locked after extraction, verification/geolocation can only update existing records by id)
 * is workflow-agnostic and the same {@link StoreAuditDatasetService} pattern can back a similarly
 * named tool for a future multi-step extraction workflow without redesign.</p>
 */
@Service
public class StoreDatasetTool implements JarvisTool, ToolSchemaProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(StoreDatasetTool.class);
    private static final String TOOL_NAME = "storeDataset";

    // Not actually required in practice while a Store Audit workflow is active in the native tool
    // loop: NativeToolLoopService owns the canonical active dataset's identity and injects this
    // argument automatically when omitted, so the model never has to remember/repeat a dataset
    // UUID it was already given. Kept as a normal (now optional) argument, not removed from the
    // schema, so explicit out-of-workflow use (e.g. GET_DATASET on a specific known id) still
    // works; supplying an id that does not match the active workflow's dataset is rejected as
    // STORE_DATASET_ID_MISMATCH before this tool is ever called.
    private static final String DATASET_ID_ARG_DESCRIPTION =
            "Dataset id. Usually not needed - while a Store Audit workflow is active, Core automatically "
                    + "targets the active canonical dataset even if this is omitted. Only supply this explicitly "
                    + "for standalone/administrative use outside an active workflow.";

    // Nested schema shared by CREATE_DATASET/START_DATASET/APPEND_RECORDS - fullAddress is the only
    // field the JSON schema itself marks required; provenance (sourceAttachmentIndex preferred,
    // sourceAttachmentId as a fallback for explicit typed-list input with no attachments) is
    // enforced by StoreAuditDatasetService instead of the schema, since exactly which of the two is
    // required depends on whether this message has attachments at all. The rest are descriptive and
    // optional so a partially-legible source row is still accepted rather than rejected outright.
    private static final ToolJsonSchema RECORD_SCHEMA = ToolJsonSchema.object(
            recordProperties(), List.of("fullAddress"),
            "One extracted store record");
    private static final ToolJsonSchema RECORDS_SCHEMA = ToolJsonSchema.arrayOf(
            RECORD_SCHEMA, "Extracted records: array of objects, never a JSON-encoded string");
    private static final ToolJsonSchema SOURCE_ATTACHMENT_IDS_SCHEMA = ToolJsonSchema.arrayOf(
            ToolJsonSchema.string("A current-message attachment id"),
            "Current-message attachment ids the records were extracted from - array of strings");
    private static final ToolJsonSchema VERIFICATION_ENTRY_SCHEMA = ToolJsonSchema.object(
            verificationEntryProperties(), List.of("recordId", "status"),
            "One record's verification status/correction");
    private static final ToolJsonSchema VERIFICATIONS_SCHEMA = ToolJsonSchema.arrayOf(
            VERIFICATION_ENTRY_SCHEMA, "Per-record verification results, referencing existing record ids only");
    private static final ToolJsonSchema SCHEDULE_DAY_SCHEMA = ToolJsonSchema.object(
            scheduleDayProperties(), List.of("day", "storeIds"),
            "One day's grouping of store record ids");
    private static final ToolJsonSchema DAYS_SCHEMA = ToolJsonSchema.arrayOf(
            SCHEDULE_DAY_SCHEMA, "Day-by-day grouping - every dataset record id exactly once across all days");

    private static Map<String, ToolJsonSchema> recordProperties() {
        Map<String, ToolJsonSchema> properties = new LinkedHashMap<>();
        properties.put("network", ToolJsonSchema.string("Store network/chain name"));
        properties.put("city", ToolJsonSchema.string("City"));
        properties.put("street", ToolJsonSchema.string("Street name"));
        properties.put("buildingNumber", ToolJsonSchema.string("Building number"));
        properties.put("postalCode", ToolJsonSchema.string("Postal code"));
        properties.put("fullAddress", ToolJsonSchema.string("Full postal address - required, the record is rejected without it"));
        properties.put("sourceAttachmentIndex", ToolJsonSchema.integer(
                "PREFERRED provenance field: 1-based position of the current-message attachment this record was "
                        + "read from, exactly as numbered in the 'CURRENT MESSAGE ATTACHMENTS' list (Image 1 = 1, "
                        + "Image 2 = 2, ...). Never invent or guess a value, and never reference an attachment from "
                        + "a previous message - only this message's own attachments are valid. Core resolves this "
                        + "to the real attachment id itself; you never need to know or copy that id."));
        properties.put("sourceAttachmentId", ToolJsonSchema.string(
                "Fallback provenance field, only for the rare case of an explicit user-typed record list with no "
                        + "image attachments at all. When this message has image attachments, use "
                        + "sourceAttachmentIndex instead - do not copy an attachment id string by hand."));
        properties.put("sourceRow", ToolJsonSchema.integer("Row/line number within the source attachment, for traceability"));
        return properties;
    }

    private static Map<String, ToolJsonSchema> verificationEntryProperties() {
        Map<String, ToolJsonSchema> properties = new LinkedHashMap<>();
        properties.put("recordId", ToolJsonSchema.string("Existing record id from the locked dataset"));
        properties.put("status", ToolJsonSchema.string("VERIFIED or CORRECTED"));
        properties.put("correctedFullAddress", ToolJsonSchema.string("New full address, only when status=CORRECTED"));
        properties.put("correctedPostalCode", ToolJsonSchema.string("New postal code, only when status=CORRECTED"));
        return properties;
    }

    private static Map<String, ToolJsonSchema> scheduleDayProperties() {
        Map<String, ToolJsonSchema> properties = new LinkedHashMap<>();
        properties.put("day", ToolJsonSchema.integer("Day number within the schedule"));
        properties.put("storeIds", ToolJsonSchema.arrayOf(
                ToolJsonSchema.string("A dataset record id"), "Record ids scheduled on this day"));
        return properties;
    }

    private final StoreAuditDatasetService datasetService;

    /**
     * Creates the store dataset tool.
     *
     * @param datasetService canonical dataset store
     */
    public StoreDatasetTool(StoreAuditDatasetService datasetService) {
        this.datasetService = datasetService;
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return "Holds the canonical, locked dataset of store records extracted from the current "
                + "message's attachments for a Store Audit scheduling task - the single source of "
                + "truth every later stage (verification, geolocation, optimization) must reference "
                + "by record id instead of re-deriving the list from memory.";
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(TOOL_NAME, getDescription(), List.of(
                operation("CREATE_DATASET",
                        "Submits the full list of store records extracted from the current message's "
                                + "attachments, ONCE, after reading all of them. Locks the canonical record "
                                + "count - call this exactly once per extraction, not incrementally. Every "
                                + "record must carry sourceAttachmentIndex - the 1-based position of the "
                                + "current-message attachment it was actually read from (Image 1 = 1, Image 2 = 2, "
                                + "...); a record without valid provenance is rejected, not silently accepted. "
                                + "Never call this with records copied from "
                                + "workflow documentation, Knowledge examples, or conversation history - only "
                                + "from the current message's own attachments or an explicit user-typed list. "
                                + "Returns the locked dataset with assigned record ids - use those ids in all "
                                + "later storeDataset/location calls for this task. For a LARGE extraction "
                                + "(roughly more than 10 records), submitting the entire list as one call's "
                                + "argument can fail outright - prefer START_DATASET with a smaller first batch, "
                                + "then APPEND_RECORDS for the rest, then FINALIZE_DATASET instead.",
                        true, ToolSafetyLevel.WRITE,
                        arg("sourceImageCount", "number", true, "Number of image attachments read during extraction"),
                        arg("expectedRecordCount", "number", true,
                                "Total number of store records you counted across ALL source material before "
                                        + "submitting - the exact number of rows you intend to extract in total. "
                                        + "Checked against what is actually accepted; a mismatch rejects the dataset "
                                        + "so an incomplete extraction can never silently lock in short."),
                        arg("sourceAttachmentIds", true, SOURCE_ATTACHMENT_IDS_SCHEMA),
                        arg("records", true, RECORDS_SCHEMA)),
                operation("START_DATASET",
                        "Starts building a dataset incrementally with the FIRST batch of extracted records "
                                + "(e.g. 5-8 at a time), for extractions too large to reliably submit in one "
                                + "CREATE_DATASET call. The dataset stays in stage=BUILDING - not yet usable by "
                                + "VERIFY_DATASET/GEOCODE_DATASET/SUBMIT_SCHEDULE - until FINALIZE_DATASET is "
                                + "called. Same arguments and provenance rules as CREATE_DATASET; only call this "
                                + "once per extraction (use APPEND_RECORDS with the returned datasetId for every "
                                + "further batch). It is legal for this first batch to end up with 0 accepted "
                                + "records - APPEND_RECORDS can still add the rest - but FINALIZE_DATASET will "
                                + "reject an empty or incomplete dataset.",
                        true, ToolSafetyLevel.WRITE,
                        arg("sourceImageCount", "number", true, "Number of image attachments read during extraction"),
                        arg("expectedRecordCount", "number", true,
                                "Total number of store records you counted across ALL source material before "
                                        + "starting - the exact number of rows you intend to extract in total across "
                                        + "this batch and every APPEND_RECORDS batch that follows. FINALIZE_DATASET "
                                        + "rejects the dataset if the final accepted count does not match this."),
                        arg("sourceAttachmentIds", true, SOURCE_ATTACHMENT_IDS_SCHEMA),
                        arg("records", true, RECORDS_SCHEMA)),
                operation("APPEND_RECORDS",
                        "Appends another batch of extracted records to a dataset still in stage=BUILDING "
                                + "(started with START_DATASET). Call this as many times as needed with small "
                                + "batches until every record from the source material has been submitted, then "
                                + "call FINALIZE_DATASET. Fails if the dataset is already finalized - use "
                                + "VERIFY_DATASET to correct a finalized dataset instead.",
                        true, ToolSafetyLevel.WRITE,
                        arg("datasetId", "string", false, DATASET_ID_ARG_DESCRIPTION),
                        arg("records", true, RECORDS_SCHEMA)),
                operation("FINALIZE_DATASET",
                        "Locks the record count of a dataset still in stage=BUILDING, once every batch from "
                                + "the source material has been submitted via START_DATASET/APPEND_RECORDS. "
                                + "After this, the dataset behaves exactly like one created with CREATE_DATASET - "
                                + "advancing to stage=EXTRACTED, ready for VERIFY_DATASET. Rejected if the dataset "
                                + "has 0 records, if the accepted count does not match the expectedRecordCount "
                                + "declared at START_DATASET time, or if any of the dataset's real source "
                                + "attachments contributed zero records - in every case nothing is left unfixably "
                                + "broken, call APPEND_RECORDS with the missing records and finalize again. Safe to "
                                + "call again if already finalized.",
                        true, ToolSafetyLevel.WRITE,
                        arg("datasetId", "string", false, DATASET_ID_ARG_DESCRIPTION)),
                operation("VERIFY_DATASET",
                        "Submits a second-pass verification of the ALREADY-LOCKED dataset (stage=EXTRACTED) - "
                                + "reports per-record status/corrections by record id, never a new independently "
                                + "regenerated list. REQUIRED before GEOCODE_DATASET - geolocation is rejected on a "
                                + "dataset that has not passed through this stage. Must cover every canonical record "
                                + "id in the dataset EXACTLY ONCE - a partial pass (e.g. verifying only 1 of 23 "
                                + "records) is rejected outright, not silently accepted as a full verification. If "
                                + "this pass is missing any record id, duplicates one, or references an unknown id, "
                                + "Core rejects the whole pass (nothing is applied, stage stays EXTRACTED) and lists "
                                + "the exact missing/duplicate/unknown ids so you can resubmit a corrected pass. "
                                + "Call GET_DATASET first if you are not certain of the exact current record ids. "
                                + "On success the dataset advances to stage=LOCKED.",
                        true, ToolSafetyLevel.WRITE,
                        arg("datasetId", "string", false, DATASET_ID_ARG_DESCRIPTION),
                        arg("verifications", true, VERIFICATIONS_SCHEMA)),
                operation("GET_DATASET",
                        "Returns the current canonical dataset (all records with their ids, addresses, "
                                + "verification/geolocation status, and any previously accepted schedule). Use "
                                + "this whenever you need to check the exact current record list/ids instead of "
                                + "relying on memory of an earlier turn, or to continue a dataset from an earlier "
                                + "turn in this same conversation.",
                        false, ToolSafetyLevel.READ,
                        arg("datasetId", "string", false, DATASET_ID_ARG_DESCRIPTION
                                + " Supply an explicit id here to inspect a specific dataset outside the active "
                                + "workflow (e.g. an administrative/diagnostic lookup); while a Store Audit "
                                + "workflow is active, an explicit id must match the active dataset.")),
                operation("SUBMIT_SCHEDULE",
                        "Submits a proposed day-by-day grouping of the locked dataset's records for validation. "
                                + "Every record id in the dataset must appear in exactly one day, exactly once - no "
                                + "record missing, none duplicated, no unknown/invented id. The whole submission is "
                                + "rejected (nothing applied) if any of that is violated, with the exact missing/"
                                + "duplicate/unknown ids listed so you can resubmit a corrected grouping. Call "
                                + "GET_DATASET first if you are not certain of the exact current record ids. This "
                                + "is required before presenting a final schedule to the user - never hand-count "
                                + "or eyeball that every store was scheduled exactly once.",
                        true, ToolSafetyLevel.WRITE,
                        arg("datasetId", "string", false, DATASET_ID_ARG_DESCRIPTION),
                        arg("days", true, DAYS_SCHEMA))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        StoreDatasetOperation operation = operation(request);
        LOGGER.info("[STORE_AUDIT] requestId={} storeDataset operation={}", request.requestId(), operation);
        return switch (operation) {
            case CREATE_DATASET -> create(request);
            case START_DATASET -> start(request);
            case APPEND_RECORDS -> append(request);
            case FINALIZE_DATASET -> finalizeDataset(request);
            case VERIFY_DATASET -> verify(request);
            case GET_DATASET -> get(request);
            case SUBMIT_SCHEDULE -> submitSchedule(request);
        };
    }

    private ToolResult create(ToolRequest request) {
        int sourceImageCount = intArg(request, "sourceImageCount");
        int expectedRecordCount = intArg(request, "expectedRecordCount");
        List<String> sourceAttachmentIds = stringListArg(request, "sourceAttachmentIds");
        List<CandidateRecord> candidates = candidatesFromRecordsArg(request);
        CreateOutcome outcome = datasetService.createDataset(
                request.requestId(), sourceImageCount, expectedRecordCount, sourceAttachmentIds, candidates);
        return createOutcomeResult(request, "CREATE_DATASET", outcome);
    }

    private ToolResult start(ToolRequest request) {
        int sourceImageCount = intArg(request, "sourceImageCount");
        int expectedRecordCount = intArg(request, "expectedRecordCount");
        List<String> sourceAttachmentIds = stringListArg(request, "sourceAttachmentIds");
        List<CandidateRecord> candidates = candidatesFromRecordsArg(request);
        CreateOutcome outcome = datasetService.startDataset(
                request.requestId(), sourceImageCount, expectedRecordCount, sourceAttachmentIds, candidates);
        return createOutcomeResult(request, "START_DATASET", outcome);
    }

    private ToolResult createOutcomeResult(ToolRequest request, String operationName, CreateOutcome outcome) {
        if (!outcome.success()) {
            // On STORE_DATASET_DUPLICATE_SOURCE, outcome.dataset() is the pre-existing dataset the
            // model should use instead - surface its data so the model can act on it directly
            // (GET_DATASET/VERIFY_DATASET) rather than only reading the id out of free text.
            Map<String, Object> data = outcome.dataset() == null ? Map.of() : datasetData(outcome.dataset());
            String errorCode = outcome.errorCode().isBlank() ? "STORE_DATASET_PROVENANCE_INVALID" : outcome.errorCode();
            return new ToolResult(false, TOOL_NAME, operationName, request.requestId(), request.conversationId(),
                    false, List.of(), outcome.message(), data, errorCode, outcome.message(), false, "");
        }
        Map<String, Object> data = datasetData(outcome.dataset());
        data.put("acceptedCount", outcome.acceptedCount());
        data.put("duplicateCount", outcome.duplicateCount());
        data.put("rejected", outcome.rejected().stream()
                .map(candidate -> Map.<String, Object>of("index", candidate.index(), "reason", candidate.reason()))
                .toList());
        return new ToolResult(true, TOOL_NAME, operationName, request.requestId(), request.conversationId(),
                true, List.of(outcome.dataset().datasetId()), outcome.message(), data, "", "", false, "");
    }

    private ToolResult append(ToolRequest request) {
        String datasetId = arg(request, "datasetId");
        List<CandidateRecord> candidates = candidatesFromRecordsArg(request);
        AppendOutcome outcome = datasetService.appendRecords(datasetId, candidates);
        if (!outcome.success()) {
            Map<String, Object> data = outcome.dataset() == null ? Map.of() : datasetData(outcome.dataset());
            return new ToolResult(false, TOOL_NAME, "APPEND_RECORDS", request.requestId(), request.conversationId(),
                    false, List.of(), outcome.message(), data, outcome.errorCode(), outcome.message(), false, "");
        }
        Map<String, Object> data = datasetData(outcome.dataset());
        data.put("acceptedCount", outcome.acceptedCount());
        data.put("duplicateCount", outcome.duplicateCount());
        data.put("rejected", outcome.rejected().stream()
                .map(candidate -> Map.<String, Object>of("index", candidate.index(), "reason", candidate.reason()))
                .toList());
        return new ToolResult(true, TOOL_NAME, "APPEND_RECORDS", request.requestId(), request.conversationId(),
                true, List.of(datasetId), outcome.message(), data, "", "", false, "");
    }

    private ToolResult finalizeDataset(ToolRequest request) {
        String datasetId = arg(request, "datasetId");
        FinalizeOutcome outcome = datasetService.finalizeDataset(datasetId);
        if (!outcome.success()) {
            Map<String, Object> data = outcome.dataset() == null ? Map.of() : datasetData(outcome.dataset());
            return new ToolResult(false, TOOL_NAME, "FINALIZE_DATASET", request.requestId(), request.conversationId(),
                    false, List.of(), outcome.message(), data, outcome.errorCode(), outcome.message(), false, "");
        }
        return new ToolResult(true, TOOL_NAME, "FINALIZE_DATASET", request.requestId(), request.conversationId(),
                true, List.of(datasetId), outcome.message(), datasetData(outcome.dataset()), "", "", false, "");
    }

    private List<CandidateRecord> candidatesFromRecordsArg(ToolRequest request) {
        List<CandidateRecord> candidates = new ArrayList<>();
        for (Object raw : listArg(request, "records")) {
            if (raw instanceof Map<?, ?> map) {
                candidates.add(new CandidateRecord(
                        textField(map, "network"), textField(map, "city"), textField(map, "street"),
                        textField(map, "buildingNumber"), textField(map, "postalCode"), textField(map, "fullAddress"),
                        textField(map, "sourceAttachmentId"), intField(map, "sourceRow"),
                        integerField(map, "sourceAttachmentIndex")));
            }
        }
        return candidates;
    }

    private ToolResult verify(ToolRequest request) {
        String datasetId = arg(request, "datasetId");
        List<VerificationEntry> verifications = new ArrayList<>();
        for (Object raw : listArg(request, "verifications")) {
            if (raw instanceof Map<?, ?> map) {
                verifications.add(new VerificationEntry(
                        textField(map, "recordId"), textField(map, "status"),
                        textField(map, "correctedFullAddress"), textField(map, "correctedPostalCode")));
            }
        }
        VerifyOutcome outcome = datasetService.verifyDataset(datasetId, verifications);
        if (!outcome.success()) {
            String errorCode = outcome.invariantViolation() ? "STORE_DATASET_INVARIANT_VIOLATION" : "STORE_DATASET_NOT_FOUND";
            Map<String, Object> data = outcome.dataset() == null ? Map.of() : datasetData(outcome.dataset());
            data = new HashMap<>(data);
            data.put("missingRecordIds", outcome.missingRecordIds());
            data.put("duplicateRecordIds", outcome.duplicateRecordIds());
            data.put("unknownRecordIds", outcome.unknownRecordIds());
            return new ToolResult(false, TOOL_NAME, "VERIFY_DATASET", request.requestId(), request.conversationId(),
                    false, List.of(), outcome.message(), data, errorCode, outcome.message(), false, "");
        }
        return new ToolResult(true, TOOL_NAME, "VERIFY_DATASET", request.requestId(), request.conversationId(),
                true, List.of(datasetId), outcome.message(), datasetData(outcome.dataset()), "", "", false, "");
    }

    private ToolResult get(ToolRequest request) {
        String datasetId = arg(request, "datasetId");
        Optional<StoreAuditDataset> dataset = datasetService.getDataset(datasetId);
        if (dataset.isEmpty()) {
            String message = "Unknown or expired dataset id: " + datasetId;
            return new ToolResult(false, TOOL_NAME, "GET_DATASET", request.requestId(), request.conversationId(),
                    false, List.of(), message, Map.of(), "STORE_DATASET_NOT_FOUND", message, false, "");
        }
        return new ToolResult(true, TOOL_NAME, "GET_DATASET", request.requestId(), request.conversationId(),
                false, List.of(datasetId), "Dataset has " + dataset.get().stores().size() + " record(s).",
                datasetData(dataset.get()), "", "", false, "");
    }

    private ToolResult submitSchedule(ToolRequest request) {
        String datasetId = arg(request, "datasetId");
        List<ScheduleDay> days = new ArrayList<>();
        for (Object raw : listArg(request, "days")) {
            if (raw instanceof Map<?, ?> map) {
                days.add(new ScheduleDay(intField(map, "day"), stringListField(map, "storeIds")));
            }
        }
        ScheduleSubmitOutcome outcome = datasetService.submitSchedule(datasetId, days);
        if (!outcome.success()) {
            String errorCode = outcome.invariantViolation() ? "STORE_DATASET_INVARIANT_VIOLATION" : "STORE_DATASET_NOT_FOUND";
            Map<String, Object> data = outcome.dataset() == null ? Map.of() : datasetData(outcome.dataset());
            data = new HashMap<>(data);
            data.put("missingStoreIds", outcome.missingStoreIds());
            data.put("unknownStoreIds", outcome.unknownStoreIds());
            data.put("duplicateStoreIds", outcome.duplicateStoreIds());
            return new ToolResult(false, TOOL_NAME, "SUBMIT_SCHEDULE", request.requestId(), request.conversationId(),
                    false, List.of(), outcome.message(), data, errorCode, outcome.message(), false, "");
        }
        return new ToolResult(true, TOOL_NAME, "SUBMIT_SCHEDULE", request.requestId(), request.conversationId(),
                true, List.of(datasetId), outcome.message(), datasetData(outcome.dataset()), "", "", false, "");
    }

    private Map<String, Object> datasetData(StoreAuditDataset dataset) {
        Map<String, Object> data = new HashMap<>();
        data.put("datasetId", dataset.datasetId());
        data.put("conversationId", dataset.conversationId());
        data.put("stage", dataset.stage().name());
        data.put("count", dataset.stores().size());
        data.put("sourceImageCount", dataset.sourceImageCount());
        if (dataset.expectedRecordCount() > 0) {
            data.put("expectedRecordCount", dataset.expectedRecordCount());
        }
        data.put("sourceAttachmentIds", dataset.sourceAttachmentIds());
        data.put("records", dataset.stores().stream().map(this::recordMap).toList());
        if (!dataset.schedule().isEmpty()) {
            data.put("schedule", dataset.schedule().stream().map(this::scheduleDayMap).toList());
        }
        return data;
    }

    private Map<String, Object> scheduleDayMap(ScheduleDay day) {
        Map<String, Object> map = new HashMap<>();
        map.put("day", day.day());
        map.put("storeIds", day.storeIds());
        return map;
    }

    private Map<String, Object> recordMap(StoreRecord record) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", record.id());
        map.put("network", record.network());
        map.put("city", record.city());
        map.put("street", record.street());
        map.put("buildingNumber", record.buildingNumber());
        map.put("postalCode", record.postalCode());
        map.put("fullAddress", record.fullAddress());
        map.put("sourceAttachmentId", record.sourceAttachmentId());
        map.put("sourceRow", record.sourceRow());
        map.put("verificationStatus", record.verificationStatus().name());
        map.put("geolocationStatus", record.geolocationStatus().name());
        if (record.latitude() != null) {
            map.put("latitude", record.latitude());
        }
        if (record.longitude() != null) {
            map.put("longitude", record.longitude());
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // Argument resolution helpers
    // ---------------------------------------------------------------------

    private String arg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private int intArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private List<Object> listArg(ToolRequest request, String name) {
        Object value = request.arguments().get(name);
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of();
    }

    private List<String> stringListArg(ToolRequest request, String name) {
        List<String> result = new ArrayList<>();
        for (Object item : listArg(request, name)) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item).strip());
            }
        }
        return result;
    }

    private String textField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).strip();
    }

    private int intField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Integer integerField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private List<String> stringListField(Map<?, ?> map, String key) {
        Object value = map.get(key);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    result.add(String.valueOf(item).strip());
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Schema helpers
    // ---------------------------------------------------------------------

    private ToolOperationDefinition operation(String name, String description, boolean write,
            ToolSafetyLevel safetyLevel, ToolArgumentDefinition... arguments) {
        return new ToolOperationDefinition(name, description, List.of(arguments), write, safetyLevel);
    }

    private ToolArgumentDefinition arg(String name, String type, boolean required, String description) {
        return new ToolArgumentDefinition(name, type, required, description);
    }

    private ToolArgumentDefinition arg(String name, boolean required, ToolJsonSchema schema) {
        return new ToolArgumentDefinition(name, required, schema);
    }

    private StoreDatasetOperation operation(ToolRequest request) {
        if (request == null) {
            throw new ToolException("Tool request is required");
        }
        try {
            return StoreDatasetOperation.valueOf(request.operation() == null ? "" : request.operation().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ToolException("Unsupported storeDataset operation: " + request.operation(), exception);
        }
    }
}
