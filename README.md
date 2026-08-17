# Jarvis

Jarvis (J.A.R.V.I.S. Core) is a long-term AI operating system backend foundation: a headless Spring Boot service that orchestrates local Ollama models behind a provider-independent AI contract, with brain routing, native tool calling, a Knowledge Workspace, web/marketplace/location tools, cognitive memory, and real-time streaming to a separate desktop client.

Current version: **`2.9.0`**. Runs on Java 21 with Maven, targets Ubuntu Server 24.04 LTS or Windows, and talks to a local Ollama instance for inference.

## Requirements

- Java 21 (JDK)
- Maven 3.8+
- A running [Ollama](https://ollama.com) instance (default `http://localhost:11434`) with at least one pulled model (default `gpt-oss:20b`)
- Optional: a local [SearXNG](https://docs.searxng.org/) instance for live web/marketplace search (see [Web Search](#web-search))
- Optional: internet access to the public [OpenStreetMap Nominatim](https://nominatim.openstreetmap.org) and [OSRM](https://router.project-osrm.org) instances for the [Location](#location--geocoding--routing) tool, or a self-hosted equivalent
- The separate Windows desktop client ([D:\J.AR.V.I.S\WINDOWS-claude\WINDOWS](../WINDOWS-claude/WINDOWS)) is optional - Core is a fully independent headless service reachable over plain HTTP/SSE/WebSocket by any client

## Modules

- `jarvis-common` - shared DTOs, constants, and cross-module types.
- `jarvis-brain-router` - logical brain catalog and deterministic brain routing.
- `jarvis-api` - REST controllers and WebSocket endpoint configuration.
- `jarvis-knowledge` - metadata-only knowledge document index and filesystem watcher.
- `jarvis-core` - Spring Boot application, prompt building, image attachments, and orchestration services.
- `jarvis-memory` - the cognitive pipeline (stage-by-stage request processing), conversation history, and tool-calling orchestration.
- `jarvis-ollama` - Ollama implementation hidden behind the provider-independent AI contract, model management, and the Qwen thinking-budget mechanism.
- `jarvis-planner` - planning contracts for future planning engines.
- `jarvis-tools` - native tool execution framework: `KnowledgeTool`, `WebSearchTool` (web + marketplace), and `LocationTool` (geocoding/routing).
- `jarvis-plugin-sdk` - plugin extension contracts for future external JAR plugins.

## Running & Building

Run the backend directly:

```bash
mvn clean spring-boot:run -pl jarvis-core -am
```

Build every module offline against the local Maven cache (used throughout this project's own CI/dev workflow):

```bash
mvn -o install -DskipTests
```

Run the full test suite:

```bash
mvn -o test
```

## Architecture Overview

Every chat request runs through a fixed, ordered pipeline of stages (`jarvis-memory/src/main/java/com/jarvis/memory/pipeline/`, executed by `CognitivePipelineExecutor`). Each stage receives and returns an immutable `PipelineContext`:

```text
ValidationStage
  -> ImageAttachmentStage        (resolves any image attachments to native vision bytes)
  -> TaskAnalysisStage
  -> MemoryRetrievalStage
  -> ComplexityStage
  -> ConversationContextStage    (persists the user turn, loads/reduces recent history)
  -> KnowledgeStage
  -> ExecutionPlanStage          (brain/model routing)
  -> KnowledgeModeStage
  -> KnowledgeRetrievalStage
  -> ContextBuilderStage
  -> PromptBuilderStage
  -> ModelExecutionStage         (main model call; parses FINAL_ANSWER / TOOL_REQUEST / CLARIFICATION)
  -> ToolCallingStage            (only when the model returned TOOL_REQUEST)
  -> MemoryUpdateStage
  -> ResponseValidationStage     (persists the assistant turn)
```

The main model's structured response is always one of exactly three types (`MainModelActionType`): `FINAL_ANSWER`, `TOOL_REQUEST`, or `CLARIFICATION`. A `TOOL_REQUEST` carries a free-text `goal`/`reason` - it never names a specific tool. Which tool actually runs is decided entirely by the model itself through native function calling (see [Native Tool Calling](#native-tool-calling--tool-loop)), not by any keyword-based router in Core.

## Configuration

All configuration lives under the `jarvis:` root key in `jarvis-core/src/main/resources/application.yml` (or an environment-specific override). Key sections:

```yaml
jarvis:
  version: "2.9.0"
  ai:
    identity-file: file:config/jarvis.md
    context-window: 16384
    reserved-output-tokens: 2048
  workspace:               # temporary attachment storage (see File Workspace & Attachments)
    root: ./temp-workspaces
    ttl: 60m
  tools:                    # native tool-calling runtime budgets (see Native Tool Calling)
    enabled: true
    runtime: native
    max-calls-fast: 8
    max-calls-research: 15
    max-consecutive-failures: 2
    max-consecutive-operation-repeats: 5
    timeout-seconds: 600
  web-search:               # SearXNG-backed web + marketplace search (see Web Search)
    enabled: true
    base-url: http://127.0.0.1:8888
  location:                 # geocoding/routing/route-optimization (see Location)
    enabled: true
    nominatim-base-url: https://nominatim.openstreetmap.org
    osrm-base-url: https://router.project-osrm.org
    user-agent: "JARVIS-Core-LocationTool/1.0 (...)"
  thinking:                 # streams the model's reasoning tokens to clients (see Thinking)
    stream-to-clients: true
    persist: false
  model:
    active-model-file: file:config/active-model.txt
  ollama:
    base-url: http://localhost:11434
    model: gpt-oss:20b
  qwen-thinking-budget:      # see Qwen Thinking Budget
    target-model: qwen3.5:9b
    mode: NORMAL
  conversation:
    history:
      max-messages: 30
      max-characters: 30000

brains:
  FAST: {provider: ollama, model: gpt-oss:20b}
  REASONING: {provider: ollama, model: gpt-oss:20b}
  CLASSIFIER: {provider: ollama, model: gpt-oss:20b}

knowledge:
  root: ./knowledge
  watch: true
```

See `jarvis-core/src/main/resources/application.yml` for every key and its default. The AI's identity/persona is loaded at runtime from `config/jarvis.md`, not compiled into the jar.

## API Endpoints

| Path | Purpose |
|---|---|
| `GET /api/health` | Liveness/version check |
| `POST /api/v1/chat` | Send a chat message, get a full response |
| `POST /api/v1/chat/stream` | Submit a chat request body ahead of streaming; returns a short-lived token |
| `GET /api/v1/chat/stream?token=...` | Server-Sent Events streaming chat, for a request submitted via the `POST` above |
| `WS /ws/jarvis` | WebSocket streaming chat (used by the Windows client's live connection) |
| `GET/POST /api/models`, `POST /api/models/active` | List installed Ollama models and switch the active one |
| `GET/POST /api/v1/knowledge/*` | Knowledge Workspace CRUD, search, retrieval, drafts |
| `POST /api/v1/context/build`, `POST /api/v1/prompt/debug` | Inspect context/prompt assembly without invoking Ollama |
| `GET /api/v1/events/schema`, `/sample` | Cognitive event catalog and sample payloads |
| `POST/GET/DELETE /api/v1/workspaces/*` | Temporary attachment workspace lifecycle |
| `POST/GET/DELETE /api/v1/conversations/*` | Conversation history management |
| `GET/POST /api/v1/memory/*` | Cognitive memory search/reindex/legacy migration |
| `GET /api/v1/tools`, `/api/v1/tools/requests/{id}` | Registered native tool catalog and past tool-call requests |
| `GET /api/v1/cognitive-graph`, `/debug` | Cognitive event graph for the diagnostics UI |
| `GET /api/v1/debug/*`, `/api/v1/research/requests/{id}` | Pipeline/request/tool-loop debug snapshots |
| `POST /api/v1/router/analyze`, `/compare` | Brain-routing decision inspection |

## Core Features

### Brain Routing & Model Selection

`RuleBasedBrainRouter` (`jarvis-brain-router`) picks a logical `Brain` (`FAST`/`REASONING`/`CLASSIFIER`) and reasoning level (`LOW`/`MEDIUM`/`HIGH`) per request based on message complexity. Independently, `ActiveModelService`/`ModelController` expose the live set of Ollama-installed models (with detected capabilities: `TEXT`, `VISION`, `TOOLS`, `THINKING`, `JSON`) and let a client switch which model is currently active (`GET /api/models`, `POST /api/models/active`) - the switch takes effect for the next request, never mid-flight.

`HeuristicComplexityAnalyzer` scores complexity from more than message length: attachment count now contributes too (`+min(4, count+1)`, capped so a single casual photo question is never automatically maximal), on top of the existing task-type/keyword signals - a short one-sentence request carrying two photos to extract and schedule previously scored complexity=1 ("Low complexity", `FAST`/`LOW`) purely because nothing looked at attachments at all. `RuleBasedTaskAnalyzer`'s creation-verb (`stworz`/`napisz`/`wygeneruj`/`przygotuj`/`zaplanuj`/`zorganizuj`) and content-noun (`plan`/`document`/`grafik`/`harmonogram`/`raport`/`trasa`/...) keyword sets were broadened generically - a specific request phrase is never hardcoded, only whole verb/noun categories, so this generalizes to any similarly-shaped "prepare/plan/organize a schedule/route/report" request, not one exact reproduction sentence.

### Vision / Image Attachments

Chat requests may attach images (resolved through the same temporary-workspace mechanism as file attachments). `ImageAttachmentStage` loads them into the pipeline, and `ModelExecutionStage` gates the call behind the active model's detected `VISION` capability - a non-vision model never silently receives image bytes it can't use. Vision-capable requests are sent through Ollama's `/api/chat` transport (per-message `images` field), not `/api/generate`, because at least one real chat-templated multimodal model has been observed to silently ignore `/api/generate`'s top-level `images` field. When an image is attached, the prompt also carries an explicit `=== ATTACHED IMAGES ===` note, so a model reasoning strictly from prompt text doesn't talk itself into concluding no image was provided.

### Current-Message Attachments vs Knowledge Workspace

Images attached to the *current* user message and documents persisted in the *Knowledge Workspace* are two structurally separate data sources, and the model is explicitly told so:

- The current-message-attachments policy is injected into the main model's decision prompt only when the request actually has images (`MainModelIntegratedToolTrigger`), and the same rule is restated in the base identity prompt (`config/jarvis.md`): read attached images directly with your own vision; never ask a tool to fetch/load/analyze a current-message image; never use `KnowledgeTool` to locate one - it only searches persisted documents.
- Images attached to the current message travel *with* the request into the native tool loop, not just the single main-model decision call: `ModelMessage` carries an `images` field (by reference to the same `ImageAttachment`s `ImageAttachmentStage` resolved once - never copied), `ToolCallingRequest`/`ToolCallingStage` forward `PipelineContext#images()` into it, and `NativeToolLoopService` attaches them to the loop's initial user turn. Because the tool loop's message list is append-only and resent in full on every turn, the images only need to be attached once - they are still present on that same user message after any number of tool calls, with no separate multimodal-context object and no re-encoding per turn. `OllamaProvider.toolChat` forwards them through Ollama's `/api/chat` per-message `images` field, the same transport the plain (non-tool) vision path already uses. This replaced an earlier design where images were deliberately dropped once the native tool loop started (the model was expected to extract everything it needed into the `TOOL_REQUEST` goal text) - that broke down for high-cardinality extraction (e.g. 23 store rows read off a table don't fit losslessly into a short goal string), so the model would ask the user to resend images it had already read seconds earlier. See [Store Audit Dataset](#store-audit-dataset) for how extracted records are kept as structured state instead of prompt text, which is the other half of the same fix.
- **Defensive routing**: if the model still emits a `TOOL_REQUEST` whose goal reads as "fetch/retrieve/analyze the attached image" while the current message actually has images (`AttachmentRetrievalIntentDetector` - an action-word + attachment-noun match, not a fixed phrase list, so it generalizes across languages/wording without being tied to any one workflow), `ModelExecutionStage` does not hand that off to the tool loop. It re-asks the main model once, with a short internal corrective note appended to the prompt ("images are already in your multimodal context, do not use a tool to retrieve them"), and uses the corrected decision. This retry is capped at one attempt (`MAX_ATTACHMENT_ROUTING_RETRIES`) - if the model still gets it wrong, Core lets the request proceed rather than looping forever, relying on `KnowledgeTool`'s honest not-found reporting (see below) to keep things visible instead of silently retrying indefinitely. Diagnostics are logged under `[ATTACHMENT_ROUTING]` (attachment counts, retry attempts, recovery outcome - never image bytes/base64).
- `KnowledgeTool` itself is unchanged and still the correct tool whenever the user is actually asking about persisted knowledge (e.g. "sprawdz w zapisanej wiedzy...") - this mechanism only concerns the images attached to the current message.

### File Workspace & Attachments

Temporary attachments are not Knowledge. They are uploaded into isolated workspaces under `temp-workspaces/<workspace-id>/input`, passed to the prompt as bounded data context, and removed by TTL cleanup (default 60 minutes of inactivity).

```bash
curl -X POST "http://localhost:8080/api/v1/workspaces?conversationId=default"
curl -X POST "http://localhost:8080/api/v1/workspaces/<workspace-id>/attachments" \
  -F "files=@Example.java" -F "files=@application.yml"
```

Chat requests reference attachments by ID:

```json
{"conversationId": "default", "message": "Co robi ten plik?",
 "attachments": [{"workspaceId": "<workspace-id>", "attachmentId": "<attachment-id>"}]}
```

Supported text/code formats: `.txt .md .log .csv .java .py .js .ts .html .css .json .xml .yml .yaml .properties .sql .sh .ps1`, plus `.png .jpg .jpeg .gif .webp` for vision-capable models. Unsupported binary files are rejected before prompt construction. Path traversal, absolute paths, and duplicate names cannot escape or overwrite outside the workspace; uploads are atomic; incomplete `.partial` files are never registered.

An image-only attachment never gets its raw bytes text-injected into the prompt (that would corrupt/crash on binary data) - instead the prompt carries a short note confirming an image is attached natively, and any non-image attachments are listed under `=== TEMPORARY ATTACHMENTS ===` as before.

### Knowledge Workspace

```bash
curl http://localhost:8080/api/v1/knowledge
curl -X POST http://localhost:8080/api/v1/knowledge/reindex
curl -X POST http://localhost:8080/api/v1/knowledge/retrieve -H "Content-Type: application/json" -d '{"query":"..."}'
```

The knowledge engine keeps metadata only; source documents remain in the configured knowledge root (`./knowledge` by default) and are not moved or modified by indexing. Retrieval is hybrid keyword + embedding scoring over indexed metadata. `KnowledgeTool` (native tool `knowledge`) exposes read/create/update/append/delete/move/rename/list/search operations to the model, each going through `WorkspaceTransactionManager` for auditability.

### Native Tool Calling & Tool Loop

Every native tool is a Spring bean implementing `JarvisTool` (`getName`/`getDescription`/`execute`) plus `ToolSchemaProvider` (`definition()` describing its operations as JSON-schema-like `ToolOperationDefinition`s). `DefaultToolManager`/`DefaultToolRegistry` auto-discover every such bean via Spring's `List<JarvisTool>` injection - **adding a new tool requires no changes to any dispatch/routing code**, only the new tool class itself plus (if it needs its own configuration) one line in `jarvis-tools/src/main/java/com/jarvis/tools/ToolConfiguration.java`'s `@EnableConfigurationProperties`.

`NativeToolLoopService` drives the actual loop: it exposes the *full* tool catalog to the model on every turn (which tool to call is always the model's own decision, never Core's), executes whatever the model calls, and feeds results back until the model returns a plain-text final answer. Loop safety:

**Re-entrant by design.** `TOOL_REQUEST` is a valid action at *every* turn of this loop, not just the first one - a model that still needs a capability after several tool calls, after a `system__notify_user` status update, or during what looks like final synthesis must never have that request silently discarded. Two failure modes this specifically fixes:

- **A model writing its next tool request as plain text instead of an actual native tool call** (a formatting habit carried over from the outer `TOOL_REQUEST`/`FINAL_ANSWER`/`CLARIFICATION` JSON protocol one layer up, even though this loop already has real native tool-calling available). `detectStructuredEnvelopeType` sniffs the loop's own plain-text turn for a `{"type":"TOOL_REQUEST",...}` envelope; when found, Core pushes the turn back with a corrective system message telling the model to make the actual call, and continues the loop (bounded - `MAX_MALFORMED_CONTINUATION_ATTEMPTS`, currently 2 - after which the text is accepted as final content rather than nagging forever).
- **A tool-less "final synthesis" call** (`ToolCallingStage`'s narration turn, used only when the native loop itself ran out of budget with tool results but no final text) **itself returning another `TOOL_REQUEST` envelope.** Previously this was always converted into an apology. `ToolCallingStage.streamToolFinalSynthesis` now re-invokes the real tool runtime with that envelope's goal/reason and uses *its* result instead - bounded to `MAX_FINAL_SYNTHESIS_REENTRIES` (currently 1) re-entries, after which it falls back to the honest apology as a true last resort.

**Workflow completion gate.** A plain-text final turn is not automatically "the task is done" - if this loop actually engaged with a stateful workflow (currently: a Store Audit dataset touched via `storeDataset`/`location.GEOCODE_DATASET` in this same loop), a pluggable `WorkflowCompletionValidator` (`com.jarvis.tools.workflow`) gets the final say before the content is accepted. When it reports the workflow isn't finished, Core pushes back exactly what remains (e.g. "dataset stage=GEOLOCATED, call `storeDataset.SUBMIT_SCHEDULE`") and continues the loop instead of returning - bounded by `MAX_COMPLETION_GATE_ATTEMPTS` (currently 3). The agent loop itself stays generic (it only knows how to ask "is this workflow done?"); Store Audit provides the concrete implementation (`StoreAuditWorkflowCompletionValidator`) so a future stateful workflow can plug in its own without the loop changing. A dataset merely *existing* for the conversation (via cross-turn continuity) never triggers this - only a loop that actually touched it does, so an unrelated later turn is never blocked by someone else's in-progress dataset.

Every re-entry/gate decision is logged for one requestId as `[AGENT_LOOP] turn=N action=TOOL_REQUEST|FINAL_CONTENT|FINAL_ANSWER`, `[WORKFLOW_STATE] datasetId=... stage=... records=...`, `[NATIVE_TOOL_LOOP] ... COMPLETION_GATE complete=false reason=...`, and `[NATIVE_TOOL_LOOP] ... REENTER_TOOL_LOOP reason=...`.

**A model turn with neither a tool call nor any text content** (observed in production: a large multimodal + many-native-tool prompt the model briefly produced nothing for, after tens of seconds of "thinking") previously failed the whole task immediately on the very first empty turn, surfacing as "Nie udalo mi sie teraz zebrac wystarczajacych danych: EMPTY_MODEL_RESPONSE_WITHOUT_TOOL_CALL". This is now retried with an explicit corrective system message (`MAX_EMPTY_RESPONSE_RETRIES`, currently 2) before falling back to that same honest failure message - most transient empty turns now self-recover within the same request instead of failing outright.

Further loop safety:

- **Exact-duplicate blocking**: an identical `tool.OPERATION` call with identical arguments is blocked immediately (no re-execution).
- **No-progress guard**: the same `tool.OPERATION` called repeatedly with *different* arguments (e.g. a rewording of the same query) is blocked once it exceeds `max-consecutive-operation-repeats` (default 5), with a message telling the model to try something else or answer with what it already has.
- **Call budget**: `max-calls-fast`/`max-calls-research` (default 8/15) cap total tool calls per turn, bumped further to 12 for `SEARCH_WEB`/`LOCATION`-flavored requests. A genuinely multi-stage task (e.g. Store Audit: read workflow, create dataset, verify, geocode, optimize route, notify, final answer) easily needs 6-10 turns - too low a budget forces the loop to cut off mid-task, which previously surfaced as a rushed/malformed final tool call once the model ran out of room.
- **Hard timeout**: `timeout-seconds` (default 600s) bounds the whole loop regardless of call count - raised alongside the call budget, since more turns need more wall-clock time.
- **Failed tool results never become the answer**: a result from a rejected/invalid call (bad arguments, a duplicate, a no-progress block) carries an internal diagnostic message (e.g. `invalidResult`'s literal `"Invalid native tool call"`) - `ToolCallingStage.fallbackToolAnswer` only ever considers `success()` results when picking fallback text, so an internal error message can never leak into the chat as if it were the assistant's real answer. Invalid native tool calls are also now logged (`[NATIVE_TOOL_LOOP] ... invalid native tool call name=... arguments=... error=...`) with the exact call that failed, for diagnosing why.
- **Redundant attachment-retrieval recovery**: a `TOOL_REQUEST` asking to fetch/analyze a current-message image never reaches the tool loop at all - see [Current-Message Attachments vs Knowledge Workspace](#current-message-attachments-vs-knowledge-workspace) for the one-retry recovery that happens one layer up, in `ModelExecutionStage`, before any tool is selected.
- **No raw JSON scaffolding ever reaches the user**: if the loop's plain-text final turn parses as a `{"type":"TOOL_REQUEST",...}` envelope (the model writing out of habit the JSON protocol it was taught to use one layer up, usually with a prose preamble in front of it - `extractJson` strips that prose and still parses it), that is never legitimate final content. `ToolCallingStage.parsedStructuredToolAnswer` returns an honest fallback message instead of the raw text in that case; `FINAL_ANSWER`/`CLARIFICATION` envelopes still unwrap to their real answer/question text as before.

`ToolIntent` (`jarvis-tools/.../runtime/ToolIntent.java`) is a lightweight, **advisory-only** classifier (`DefaultToolIntentDetector`) used purely to tune the call budget and freshness heuristics - it never narrows which tools the model is allowed to see or call.

Diagnostics: `[AGENT_CONTEXT]` logs the multimodal/dataset state a tool loop starts with (image count, an existing dataset for the conversation if found, capability flags). `[AGENT_CONTEXT_CONTINUITY]` logs the canonical dataset's record count immediately before and after every `storeDataset` call, so a silent drift is visible in the logs even when nothing else fails.

### Store Audit Dataset

Multi-step extraction tasks (the motivating case: reading store lists off attached photos to build a monthly audit schedule) must never rely on the model re-deriving *how many* records exist from memory or from its own "thinking" text at every tool-loop turn - that is exactly how a genuine 23-record extraction has previously drifted to a different count several tool calls later. `StoreAuditDatasetService`/`StoreDatasetTool` (native tool `storeDataset`, `jarvis-tools/.../dataset/`) hold that count as actual application state instead:

- **`CREATE_DATASET`** locks the extracted record list once, assigning stable ids (`store-001`, ...). Every record must carry the id of a real current-message attachment (`registerAttachments` cross-checks the model's declared source against attachments Core actually resolved) - a record without valid provenance is rejected, not silently accepted, and duplicate submissions for the same source row are deduplicated rather than growing the dataset. A submission that would result in **zero** real records (an empty `records` list, or every candidate rejected for missing/invalid provenance) is rejected outright as `EMPTY_DATASET` - it never silently creates a valid-looking empty dataset the raw-geocode guard below would then treat as "a dataset exists, proceed". A **second** `CREATE_DATASET` for the exact same conversation and source attachment set is rejected as `STORE_DATASET_DUPLICATE_SOURCE`, returning the existing dataset's id - this is what stops a re-extraction from silently replacing a locked 23-record dataset with an independent 5-record one; corrections go through `VERIFY_DATASET` against the existing id instead. A different attachment set in the same conversation (genuinely new photos) is unaffected and still creates its own dataset.
- **`VERIFY_DATASET`** submits a second visual-check pass by record id. A pass reporting a record count far from the locked size, or referencing an unknown id, is rejected outright (nothing is applied) rather than trusted - the same protection extends to `GEOLOCATE`-style updates (`GeolocationEntry`), which can only enrich existing records, never create or remove one.
- **`SUBMIT_SCHEDULE`** validates a proposed day-by-day grouping against the locked dataset before it is ever presented as a final schedule: every record id must appear in exactly one day, exactly once - any missing, duplicated, or unknown/hallucinated id rejects the whole submission with the exact offending ids listed, logged as `[STORE_AUDIT_VALIDATION] datasetStores=... scheduledUniqueStores=... duplicates=... missing=... unknown=... valid=...`. This is what a schedule silently ending up with 22 or 24 stores instead of 23 is caught by.
- **`GET_DATASET`** returns the current record list, verification/geolocation status, and any accepted schedule - the reference point every later tool call re-checks against instead of trusting memory of an earlier turn.

The dataset is conversation-scoped, not just request-scoped (`StoreAuditDataset#conversationId`, 2-hour TTL with periodic sweep): `StoreAuditDatasetService#findLatestForConversation` lets a *later* chat turn ("polacz dzien 3 i 4") continue against the same canonical records without the user resending the original attachments - `NativeToolLoopService` looks this up at the start of every tool loop and, when found, tells the model the dataset id/stage/record count directly in its system prompt instead of leaving continuity to conversation-history text. Nothing here is written to the permanent Knowledge Workspace; it is working state for the current task, not long-term user knowledge.

**Enforced, not just prompted**: a system-prompt nudge to use `storeDataset` before geocoding a large extracted list was tried first and was still ignored in production (the model geocoded a handful of addresses one at a time via plain `location.GEOCODE`, ran out of tool-loop turns, and presented a "schedule" silently covering only 2 of 23 extracted stores). `NativeToolLoopService` now tracks the cumulative address count passed to raw `location.GEOCODE` calls within one loop; once that exceeds a small threshold (4) without a `storeDataset` behind it (from an earlier turn, or created earlier in this same loop), further raw `GEOCODE` calls are blocked with an instructive error - the model must call `storeDataset.CREATE_DATASET` and use `location.GEOCODE_DATASET` instead, which is exactly the path the count-invariant checks above can actually see. Small legitimate batches (a handful of route stops) stay well under the threshold and are never affected. The final-answer synthesis prompt also now requires the model to name any ambiguous/unresolved geocode result explicitly (never silently pick a candidate) and to state the dataset's exact record count when one was used.

**Large extractions can be built incrementally.** A single `CREATE_DATASET` call asking the model to populate one large `records` array (observed failing around 20+ records) can fail outright - not a parsing error, the model emits a genuinely empty `records` array despite its own reasoning stating it will populate it, a known weak spot for native tool-calling with big structured array arguments. `storeDataset.START_DATASET` begins a dataset in `DatasetStage.BUILDING` with a first small batch (5-8 records), `storeDataset.APPEND_RECORDS` adds further batches (deduplicated across every batch, not just within one), and `storeDataset.FINALIZE_DATASET` locks the record count and advances it to `EXTRACTED` - from there it behaves exactly like a dataset created in one `CREATE_DATASET` call. `VERIFY_DATASET`/`GEOCODE_DATASET`/`SUBMIT_SCHEDULE` all reject a still-`BUILDING` dataset with a clear "call FINALIZE_DATASET first" message - a half-built dataset can never be geocoded or scheduled by accident. The duplicate-source and empty-dataset invariants above apply identically to `START_DATASET`; `FINALIZE_DATASET` on an already-finalized dataset is a safe no-op (bounded retries in the agent loop above can hit this without it being a real error).

**Tool name lookup is case-insensitive**: native function names sent to the model are always lowercased (`NativeToolSchemaMapper` builds e.g. `storedataset__create_dataset` from the tool's own `getName()`), and the model echoes that lowercased name back verbatim on every call. `StoreDatasetTool#getName()` returns `"storeDataset"` (mixed case) for readability in logs/docs, which meant every real `storeDataset.CREATE_DATASET` call the model made was rejected with `Tool not registered: storedataset` - the registry (`DefaultToolManager`) was keyed by the tool's exact-case name, so the lowercase name the model actually sent never matched. In production this made the raw-geocode guard above actively harmful: the model would try `storeDataset.CREATE_DATASET` (as instructed), get an opaque "not registered" error, retry it a couple of times, give up, fall back to raw `location.GEOCODE`, and then run straight into the guard - burning most of the tool-call budget on calls that could never have succeeded. `DefaultToolManager` now normalizes both registration and lookup keys to lowercase, so any tool's `getName()` casing is safe regardless of how it is echoed back by the model.

### Web Search

J.A.R.V.I.S. can search current web information through a local self-hosted SearXNG instance. The model does not call SearXNG directly - the flow is:

```text
User -> LLM TOOL_REQUEST -> NativeToolLoopService -> WebSearchTool -> local SearXNG JSON API
     -> ToolResult -> LLM final answer -> ANSWER_SOURCES event with trusted source metadata -> User
```

The native tool `web` exposes `SEARCH_WEB` (general live search), `READ_WEB_PAGE` (fetch and normalize one result page), and `SEARCH_MARKETPLACE` (see below). The model receives normalized results only (title, URL, snippet, source) - never raw SearXNG JSON, HTML, ads, or full pages. `READ_WEB_PAGE` fetches only public `http`/`https` URLs and blocks private/local addresses.

```yaml
jarvis:
  web-search:
    enabled: true
    base-url: http://127.0.0.1:8888
```

Start SearXNG on Ubuntu: `cd /opt/jarvis/deploy/searxng && docker compose up -d`. If SearXNG is unavailable, `WebSearchTool` returns a failed `ToolResult` (`errorCode=WEB_SEARCH_FAILED`) - it never invents results.

### Marketplace Search

`web.SEARCH_MARKETPLACE` is a distinct operation from `SEARCH_WEB`, used **only** when the user wants to find a real, currently-live product listing/offer/price to buy or compare - never for general information lookups or geographic/location queries. When the model calls it, `MarketplaceListingCollector` proactively reads and AI-verifies (`AiListingVerifier`, structured ACCEPT/REJECT) candidate listing pages until it has enough *verified* concrete listings (price, condition, URL) or exhausts its read budget. Marketplace evidence is scoped strictly to that call: an unrelated `SEARCH_WEB`/`LOCATION` call made elsewhere in the same tool loop is never mistaken for marketplace research, and a marketplace sub-call that finds nothing never overrides an otherwise-complete answer the model produced for the rest of the task.

### Location / Geocoding / Routing

`LocationTool` (native tool `location`) resolves free-text addresses to coordinates and computes real road-network distances/durations/visiting orders - so the model never has to misuse web search for geographic lookups. Four operations, all read-only:

- **`GEOCODE`** - resolves one address (`query`) or a batch (`queries`) to `{latitude, longitude, displayName}`. Batch calls report `successfulPoints`/`ambiguousPoints`/`failedPoints` separately - one bad or uncertain address never fails the whole call, and every address in a batch goes through the exact same validation described below (no separate, looser path for batch calls).
- **`ROUTE`** - real road distance + driving duration between two points (`from`/`to`, each a free-text address or `{latitude,longitude}`) - never a straight-line estimate.
- **`ROUTE_MATRIX`** - an NxN road distance/duration matrix for a list of points/addresses (also accepts the operation name `DISTANCE_MATRIX` as an alias). Unreachable pairs are reported as `null` cells, not a hard failure.
- **`OPTIMIZE_ROUTE`** - given a `start` point and a list of `stops`, proposes a visiting order minimizing total distance or time (`optimize: "distance"|"time"`). This is the operation behind "group these addresses and propose a visit order", "best order to visit these stops", "plan a route through these places" style requests, regardless of whether the addresses came from typed text, an image, a file, or Knowledge.

#### Candidate-based geocoding & address validation

A geocoding provider's top result is not trusted just because its name matches - `NominatimGeocodingClient` requests several candidates per query (`addressdetails=1&limit=<geocode-candidate-limit>`, default 5) and `GeocodeCandidateScorer` (pure logic, no network I/O, in `jarvis-tools/.../location/GeocodeCandidateScorer.java`) scores each one against every address detail present in the original query text - postal code, city, street, house number, region, country - not display-name similarity alone.

Postal code is the strongest signal and is handled with three distinct outcomes, never a blind "mismatch = reject":

1. the query has a postal code and a candidate's matches it -&gt; strong positive weight
2. the query has a postal code and a candidate's disagrees -&gt; strong negative weight (effectively disqualifying)
3. the query has a postal code but the candidate didn't report one -&gt; unknown, no effect either way

City/street/region/country matches use word-boundary text matching against the query (not raw substring matching, which would e.g. false-positive-match the street "Warszawska" against the city "Warszawa"). Each query resolves to one of four statuses (`GeocodeStatus`): `RESOLVED` (a clear winner), `AMBIGUOUS` (two or more candidates scored too close together), `NOT_CONFIDENTLY_RESOLVED` (the best candidate's score was still too low to trust, e.g. it contradicts a postal code the user gave), or `NOT_FOUND` (no candidates at all). Only `RESOLVED` results are used for coordinates; the other three come back with their ranked candidate list instead of a guessed coordinate, so the model can ask the user to clarify rather than silently routing to the wrong place - this is what fixed the original bug report ("Nowa Wola 05-500" resolving to a same-named village in a different voivodeship whose postal code didn't match at all).

Geocoding and routing are two swappable, independent HTTP providers behind `GeocodingClient`/`RoutingClient` interfaces:

- **Geocoding**: [OpenStreetMap Nominatim](https://nominatim.openstreetmap.org) (free, no API key). Batch geocode calls are issued strictly sequentially with a configurable minimum interval (default 1100ms) to respect Nominatim's public-instance usage policy (~1 request/second, no unattended bulk geocoding), and a descriptive `User-Agent` is sent as required by that policy.
- **Routing**: [OSRM](https://router.project-osrm.org)'s public demo server (free, no API key), via its `/route` and `/table` endpoints.

Both base URLs are `jarvis.location.*`-configurable, so a self-hosted Nominatim/OSRM instance can replace the public ones without any code change:

```yaml
jarvis:
  location:
    enabled: true
    nominatim-base-url: https://nominatim.openstreetmap.org
    osrm-base-url: https://router.project-osrm.org
    user-agent: "JARVIS-Core-LocationTool/1.0 (contact: set-a-real-contact-before-production)"
    min-geocode-interval-millis: 1100
    max-batch-size: 25
    exact-optimization-max-stops: 8
    geocode-candidate-limit: 5
```

Visit-order optimization (`RouteOptimizer`) runs entirely locally, with **no network calls** - it operates on an already-fetched distance/duration matrix: exact brute-force search for small stop counts (`exact-optimization-max-stops`, default 8), nearest-neighbour construction + 2-opt local-search improvement above that. Points that can't be connected to the start are excluded from the route and reported back as `unresolvedStops`/`unreachableIndices`, never silently dropped or treated as zero-cost.

Neither of these public providers carries an uptime SLA - expect occasional flakiness or rate-limiting unless/until a self-hosted instance is configured.

### Thinking

When a reasoning-capable model streams a separate "thinking" channel (Ollama's native `thinking` field), Core relays it live as `THINKING`/`THINKING_TOKEN`/`THINKING_FINISHED` events (`jarvis.thinking.stream-to-clients`, default on) so a client can render a live reasoning panel. Thinking content is not persisted by default (`jarvis.thinking.persist: false`).

### Qwen Thinking Budget

Scoped **exclusively** to the exact model name `qwen3.5:9b` (case/whitespace-insensitive match, never a family prefix, never any other model) - `QwenThinkingBudgetProperties`/`OllamaProvider` cap that model's reasoning length (`OFF`/`LOW`=250/`NORMAL`=500/`HIGH`=1500/`MAX`=unlimited tokens, estimated via chars/4). If the budget is hit mid-reasoning, Core safely cancels that reasoning stream and issues a genuine second-stage call (`think:false`) to produce a complete final answer - it never truncates a response or shows the user a "budget exceeded" message; the cutoff is diagnostic-log-only and reported to clients as `thinkingBudgetMode`/`thinkingBudgetTokens`/`thinkingBudgetLimited` metadata on the generation-finished event.

```yaml
jarvis:
  qwen-thinking-budget:
    target-model: qwen3.5:9b
    mode: NORMAL   # OFF | LOW | NORMAL | HIGH | MAX
```

### MODEL PERFORMANCE

Every model turn publishes an `OllamaInferenceMetrics` payload (load time, prompt/eval token counts and rates, bottleneck classification) via the cognitive event bus. The Windows client's "Model Performance" panel renders these live, plus (only for `qwen3.5:9b`, when applicable) the Qwen thinking-budget mode/budget/tokens/limited fields described above.

### Cognitive Events

```bash
curl http://localhost:8080/api/v1/events/schema
curl http://localhost:8080/api/v1/events/sample
```

The streaming chat endpoint (`GET /api/v1/chat/stream`, and the `/ws/jarvis` WebSocket) emits unified `CognitiveEvent` payloads for request, routing, retrieval, context, prompt, model, tool, and token/completion steps. Each event includes `requestId`, `conversationId`, `timestamp`, and an optional `nodeId`; `GET /api/v1/cognitive-graph` renders the accumulated event graph for diagnostics tooling.

Server-Sent Events is GET-only by specification, so the SSE chat stream is a two-call handoff instead of putting the message body in the GET's URL: `POST /api/v1/chat/stream` submits the full `ChatRequest` (message text + attachments) and returns a short-lived, single-use `{"token": "..."}`; the client immediately opens `GET /api/v1/chat/stream?token=...` to receive the events for that exact request. `PendingChatStreamStore` holds the request server-side for up to 30 seconds between the two calls. This exists so a long message never has to travel as a URL query parameter, which would risk exceeding the embedded servlet container's default max request-line/header size (~8KB) and fail before the stream even opens. The WebSocket path is unaffected - it already sends the full request as a JSON frame body.

### Build Identification

`BuildIdentificationLogger` logs a startup banner (version, git commit, branch, build time) once the application is fully ready, so a live server's logs can always be matched back to "is this fix actually deployed yet" without guessing. Version and build time come from Spring Boot's `build-info` Maven goal (`spring-boot-maven-plugin`, `jarvis-core/pom.xml`); commit/branch are read at startup by invoking the `git` CLI directly against the working directory rather than through a build-time Maven plugin, so build identification never depends on network access to the Maven plugin repository. Every field independently falls back to `"unknown"` on any failure (no `git` binary, not a checkout, `build-info.properties` not generated, timeout, ...) - none of this can ever fail application startup.

## Windows Desktop Client

A separate JavaFX desktop client (independently versioned; see its own `README.md`) talks to Core purely over the documented HTTP/SSE/WebSocket surface above - no shared code, no private API. It provides the chat UI, a live "Thinking" panel, the MODEL PERFORMANCE dashboard, a model selector backed by `GET/POST /api/models*`, drag-and-drop/clipboard file and image attachments, and clickable source/marketplace-listing chips for web/marketplace answers.

## Known Limitations

- PDF, DOCX, XLSX, PPTX, OCR, and general archive (zip) parsing are not implemented - only the plain-text/code formats and image formats listed under [File Workspace & Attachments](#file-workspace--attachments) are supported.
- The public Nominatim/OSRM instances used by default for [Location](#location--geocoding--routing) have no uptime SLA and can rate-limit; a self-hosted instance is recommended for production use.
- Route optimization uses a heuristic (nearest-neighbour + 2-opt) above `exact-optimization-max-stops` stops - not a guaranteed-optimal TSP solution.
- Candidate-based geocoding validation reduces but does not eliminate the risk of an incorrect match - it only evaluates address details actually present in both the query text and the provider's structured response; a query with no postal code, street, or region to disambiguate a common place name can still land on `AMBIGUOUS`/`NOT_CONFIDENTLY_RESOLVED` rather than a wrong-but-confident answer, which is the intended fail-safe behavior.
- Ollama thinking-token counts are estimated (characters/4), not exact tokenizer counts, everywhere in this codebase - reported fields are explicitly labeled as estimates where relevant.
- There is no cross-repository version compatibility matrix between this backend and the Windows client; they are versioned and released independently.
- The native tool loop's re-entry/completion-gate retries (`MAX_MALFORMED_CONTINUATION_ATTEMPTS`, `MAX_COMPLETION_GATE_ATTEMPTS`, `MAX_FINAL_SYNTHESIS_REENTRIES`) are bounded heuristics, not a formal proof the model will eventually produce a genuinely complete answer - a persistently confused model still eventually gets its (possibly incomplete) text accepted once retries are exhausted, rather than hanging forever. `WorkflowCompletionValidator` currently has one concrete implementation (Store Audit); no other stateful multi-step workflow is gated on completion yet.
- `AttachmentRetrievalIntentDetector` (see [Current-Message Attachments vs Knowledge Workspace](#current-message-attachments-vs-knowledge-workspace)) matches on action-word + attachment-noun combinations, not full NLU - an unusually phrased redundant-retrieval request could in theory slip through undetected (falls back to normal tool routing/honest not-found reporting), and a legitimate goal that happens to combine both word categories without meaning "fetch the image" could in theory trigger one unnecessary internal retry; the one-retry budget bounds the cost of either case.
