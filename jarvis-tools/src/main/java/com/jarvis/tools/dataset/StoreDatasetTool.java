package com.jarvis.tools.dataset;

import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolException;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.schema.ToolArgumentDefinition;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolOperationDefinition;
import com.jarvis.tools.schema.ToolSafetyLevel;
import com.jarvis.tools.schema.ToolSchemaProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
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
                                + "record must carry the id of the actual current-message attachment it was "
                                + "read from (sourceAttachmentId); a record without valid provenance is "
                                + "rejected, not silently accepted. Never call this with records copied from "
                                + "workflow documentation, Knowledge examples, or conversation history - only "
                                + "from the current message's own attachments or an explicit user-typed list. "
                                + "Returns the locked dataset with assigned record ids - use those ids in all "
                                + "later storeDataset/location calls for this task.",
                        true, ToolSafetyLevel.WRITE,
                        arg("sourceImageCount", "number", true, "Number of image attachments read during extraction"),
                        arg("sourceAttachmentIds", "array", true, "Current-message attachment ids the records were extracted from"),
                        arg("records", "array", true, "Extracted records: [{network,city,street,buildingNumber,postalCode,fullAddress,sourceAttachmentId,sourceRow}]")),
                operation("VERIFY_DATASET",
                        "Submits a second-pass verification of the ALREADY-LOCKED dataset - reports "
                                + "per-record status/corrections by record id, never a new independently "
                                + "regenerated list. If this pass references an unknown record id or reports "
                                + "a record count far from the locked dataset size, Core rejects the whole "
                                + "pass (nothing is applied) and asks you to recheck via GET_DATASET instead "
                                + "of proceeding on a corrupted count.",
                        true, ToolSafetyLevel.WRITE,
                        arg("datasetId", "string", true, "Dataset id returned by CREATE_DATASET"),
                        arg("verifications", "array", true, "[{recordId,status,correctedFullAddress,correctedPostalCode}], status is VERIFIED or CORRECTED")),
                operation("GET_DATASET",
                        "Returns the current canonical dataset (all records with their ids, addresses, "
                                + "verification/geolocation status, and any previously accepted schedule). Use "
                                + "this whenever you need to check the exact current record list/ids instead of "
                                + "relying on memory of an earlier turn, or to continue a dataset from an earlier "
                                + "turn in this same conversation.",
                        false, ToolSafetyLevel.READ,
                        arg("datasetId", "string", true, "Dataset id returned by CREATE_DATASET")),
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
                        arg("datasetId", "string", true, "Dataset id returned by CREATE_DATASET"),
                        arg("days", "array", true, "[{day: number, storeIds: [string]}] - every dataset record id exactly once across all days"))
        ));
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        StoreDatasetOperation operation = operation(request);
        LOGGER.info("[STORE_AUDIT] requestId={} storeDataset operation={}", request.requestId(), operation);
        return switch (operation) {
            case CREATE_DATASET -> create(request);
            case VERIFY_DATASET -> verify(request);
            case GET_DATASET -> get(request);
            case SUBMIT_SCHEDULE -> submitSchedule(request);
        };
    }

    private ToolResult create(ToolRequest request) {
        int sourceImageCount = intArg(request, "sourceImageCount");
        List<String> sourceAttachmentIds = stringListArg(request, "sourceAttachmentIds");
        List<CandidateRecord> candidates = new ArrayList<>();
        for (Object raw : listArg(request, "records")) {
            if (raw instanceof Map<?, ?> map) {
                candidates.add(new CandidateRecord(
                        textField(map, "network"), textField(map, "city"), textField(map, "street"),
                        textField(map, "buildingNumber"), textField(map, "postalCode"), textField(map, "fullAddress"),
                        textField(map, "sourceAttachmentId"), intField(map, "sourceRow")));
            }
        }
        CreateOutcome outcome = datasetService.createDataset(request.requestId(), sourceImageCount, sourceAttachmentIds, candidates);
        if (!outcome.success()) {
            // On STORE_DATASET_DUPLICATE_SOURCE, outcome.dataset() is the pre-existing dataset the
            // model should use instead - surface its data so the model can act on it directly
            // (GET_DATASET/VERIFY_DATASET) rather than only reading the id out of free text.
            Map<String, Object> data = outcome.dataset() == null ? Map.of() : datasetData(outcome.dataset());
            String errorCode = outcome.errorCode().isBlank() ? "STORE_DATASET_PROVENANCE_INVALID" : outcome.errorCode();
            return new ToolResult(false, TOOL_NAME, "CREATE_DATASET", request.requestId(), request.conversationId(),
                    false, List.of(), outcome.message(), data, errorCode, outcome.message(), false, "");
        }
        Map<String, Object> data = datasetData(outcome.dataset());
        data.put("acceptedCount", outcome.acceptedCount());
        data.put("duplicateCount", outcome.duplicateCount());
        data.put("rejected", outcome.rejected().stream()
                .map(candidate -> Map.<String, Object>of("index", candidate.index(), "reason", candidate.reason()))
                .toList());
        return new ToolResult(true, TOOL_NAME, "CREATE_DATASET", request.requestId(), request.conversationId(),
                true, List.of(outcome.dataset().datasetId()), outcome.message(), data, "", "", false, "");
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
