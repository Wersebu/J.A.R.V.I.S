# Jarvis

Jarvis (J.A.R.V.I.S. Core) is a long-term AI operating system backend foundation: a headless Spring Boot service that orchestrates local Ollama models behind a provider-independent AI contract, with brain routing, native tool calling, a Knowledge Workspace, web/marketplace/location tools, cognitive memory, and real-time streaming to a separate desktop client.

Current version: **`2.8.0`**. Runs on Java 21 with Maven, targets Ubuntu Server 24.04 LTS or Windows, and talks to a local Ollama instance for inference.

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
  version: "2.8.0"
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
    max-calls-fast: 2
    max-calls-research: 8
    max-consecutive-failures: 2
    max-consecutive-operation-repeats: 5
    timeout-seconds: 180
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
| `GET /api/v1/chat/stream` | Server-Sent Events streaming chat |
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

### Vision / Image Attachments

Chat requests may attach images (resolved through the same temporary-workspace mechanism as file attachments). `ImageAttachmentStage` loads them into the pipeline, and `ModelExecutionStage` gates the call behind the active model's detected `VISION` capability - a non-vision model never silently receives image bytes it can't use. Vision-capable requests are sent through Ollama's `/api/chat` transport (per-message `images` field), not `/api/generate`, because at least one real chat-templated multimodal model has been observed to silently ignore `/api/generate`'s top-level `images` field. When an image is attached, the prompt also carries an explicit `=== ATTACHED IMAGES ===` note, so a model reasoning strictly from prompt text doesn't talk itself into concluding no image was provided.

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

- **Exact-duplicate blocking**: an identical `tool.OPERATION` call with identical arguments is blocked immediately (no re-execution).
- **No-progress guard**: the same `tool.OPERATION` called repeatedly with *different* arguments (e.g. a rewording of the same query) is blocked once it exceeds `max-consecutive-operation-repeats` (default 5), with a message telling the model to try something else or answer with what it already has.
- **Call budget**: `max-calls-fast`/`max-calls-research` cap total tool calls per turn (bumped automatically for `SEARCH_WEB`/`LOCATION`-flavored requests, which legitimately need more steps).
- **Hard timeout**: `timeout-seconds` (default 180s) bounds the whole loop regardless of call count.

`ToolIntent` (`jarvis-tools/.../runtime/ToolIntent.java`) is a lightweight, **advisory-only** classifier (`DefaultToolIntentDetector`) used purely to tune the call budget and freshness heuristics - it never narrows which tools the model is allowed to see or call.

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

- **`GEOCODE`** - resolves one address (`query`) or a batch (`queries`) to `{latitude, longitude, displayName}`. Batch calls report `successfulPoints`/`failedPoints` separately - one bad address never fails the whole call.
- **`ROUTE`** - real road distance + driving duration between two points (`from`/`to`, each a free-text address or `{latitude,longitude}`) - never a straight-line estimate.
- **`ROUTE_MATRIX`** - an NxN road distance/duration matrix for a list of points/addresses (also accepts the operation name `DISTANCE_MATRIX` as an alias). Unreachable pairs are reported as `null` cells, not a hard failure.
- **`OPTIMIZE_ROUTE`** - given a `start` point and a list of `stops`, proposes a visiting order minimizing total distance or time (`optimize: "distance"|"time"`). This is the operation behind "group these addresses and propose a visit order", "best order to visit these stops", "plan a route through these places" style requests, regardless of whether the addresses came from typed text, an image, a file, or Knowledge.

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

## Windows Desktop Client

A separate JavaFX desktop client (independently versioned; see its own `README.md`) talks to Core purely over the documented HTTP/SSE/WebSocket surface above - no shared code, no private API. It provides the chat UI, a live "Thinking" panel, the MODEL PERFORMANCE dashboard, a model selector backed by `GET/POST /api/models*`, drag-and-drop/clipboard file and image attachments, and clickable source/marketplace-listing chips for web/marketplace answers.

## Known Limitations

- PDF, DOCX, XLSX, PPTX, OCR, and general archive (zip) parsing are not implemented - only the plain-text/code formats and image formats listed under [File Workspace & Attachments](#file-workspace--attachments) are supported.
- The public Nominatim/OSRM instances used by default for [Location](#location--geocoding--routing) have no uptime SLA and can rate-limit; a self-hosted instance is recommended for production use.
- Route optimization uses a heuristic (nearest-neighbour + 2-opt) above `exact-optimization-max-stops` stops - not a guaranteed-optimal TSP solution.
- Ollama thinking-token counts are estimated (characters/4), not exact tokenizer counts, everywhere in this codebase - reported fields are explicitly labeled as estimates where relevant.
- There is no cross-repository version compatibility matrix between this backend and the Windows client; they are versioned and released independently.
