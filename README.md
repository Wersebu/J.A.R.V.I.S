# Jarvis

Jarvis (J.A.R.V.I.S. Core) is a long-term AI operating system backend foundation: a headless Spring Boot service that orchestrates local Ollama models behind a provider-independent AI contract, with brain routing, native tool calling, a Knowledge Workspace, web/marketplace/location tools, cognitive memory, and real-time streaming to a separate desktop client.

Current version: **`2.21.1-SNAPSHOT`**. Runs on Java 21 with Maven, targets Ubuntu Server 24.04 LTS or Windows, and talks to a local Ollama instance for inference.

MCP Windows bridge lifecycle is automatic: when the Windows client registers its bridge, Core asynchronously initializes enabled Windows-hosted MCP servers, discovers their tools, and refreshes MCP status without requiring manual reconnect calls. A reconnect never leaves an orphaned MCP process behind, a silently-dead process is detected and transparently relaunched instead of reused, and a genuinely empty `tools/list` result is retried a bounded number of times instead of being cached as final forever (see [Discovery Lifecycle & Reliability](docs/MCP.md#discovery-lifecycle--reliability)).

## Requirements

- Java 21 (JDK)
- Maven 3.8+
- A running [Ollama](https://ollama.com) instance (default `http://localhost:11434`) with at least one pulled model (default `gpt-oss:20b`)
- Optional: a local [SearXNG](https://docs.searxng.org/) instance for live web/marketplace search (see [Web Search](#web-search))
- Optional: internet access to the public [OpenStreetMap Nominatim](https://nominatim.openstreetmap.org) and [OSRM](https://router.project-osrm.org) instances for the [Location](#location--geocoding--routing) tool, or a self-hosted equivalent
- Optional: external MCP servers for dynamic tools, for example Roblox Studio MCP through the Windows bridge (see [Model Context Protocol](#model-context-protocol-mcp))
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
- `jarvis-tools` - native tool execution framework: `KnowledgeTool`, `WebSearchTool` (web + marketplace), `LocationTool` (geocoding/routing), and dynamic MCP tool adapters.
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

After the first successful user/assistant exchange in a new conversation, Core
starts a best-effort background title job using the same selected AI provider
and model with the `BACKGROUND` job type. The generated title is applied only
while the conversation still has the default title source; a manual rename marks
the title as user-owned and late generator results are ignored. If the model is
unavailable or returns an invalid title, Core falls back to a shortened version
of the first user message.

The main model's structured response is always one of exactly three types (`MainModelActionType`): `FINAL_ANSWER`, `TOOL_REQUEST`, or `CLARIFICATION`. A `TOOL_REQUEST` carries a free-text `goal`/`reason` - it never names a specific tool. Which tool actually runs is decided entirely by the model itself through native function calling (see [Native Tool Calling](#native-tool-calling--tool-loop)), not by any keyword-based router in Core.

## Configuration

All configuration lives under the `jarvis:` root key in `jarvis-core/src/main/resources/application.yml` (or an environment-specific override). Key sections:

```yaml
jarvis:
  version: "2.17.4"
  ai:
    identity-file: file:config/jarvis.md
    context-window: 100000
    reserved-output-tokens: 2048
  workspace:               # temporary attachment storage (see File Workspace & Attachments)
    root: ./temp-workspaces
    ttl: 60m
  websocket:               # shared realtime channel for chat, events, and Windows MCP bridge
    max-text-message-size: 4194304
    max-binary-message-size: 8388608
  tools:                    # native tool-calling runtime budgets (see Native Tool Calling)
    enabled: true
    runtime: native
    max-calls-fast: 8
    max-calls-research: 15
    max-consecutive-failures: 2
    max-consecutive-operation-repeats: 5
    stateful-workflow-min-tool-budget: 20
    max-consecutive-no-tool-progress-turns: 2
    max-live-evidence-recovery-attempts: 3
    timeout-seconds: 600
  web-search:               # SearXNG-backed web + marketplace search (see Web Search)
    enabled: true
    base-url: http://127.0.0.1:8888
  location:                 # geocoding/routing/route-optimization (see Location)
    enabled: true
    nominatim-base-url: https://nominatim.openstreetmap.org
    osrm-base-url: https://router.project-osrm.org
    user-agent: "JARVIS-Core-LocationTool/1.0 (...)"
  mcp:                      # Windows bridge enabled for Roblox Studio MCP in dev
    enabled: true
    servers:
      roblox:
        enabled: true
        execution-host: WINDOWS
        transport: WINDOWS_BRIDGE
        command: cmd.exe
        args:
          - /c
          - "cd /d %LOCALAPPDATA%\\Roblox && .\\mcp.bat"
        access-level: EDIT
  thinking:                 # streams the model's reasoning tokens to clients (see Thinking)
    stream-to-clients: true
    persist: false
  model:
    active-model-file: file:config/active-model.txt
  ollama:
    base-url: http://localhost:11434
    model: gpt-oss:20b       # configured-default fallback only, never assumed active - see Active Model
  model-startup:             # secondary models' lazy/eager policy only - see Active Model & Startup Warmup
    models:
      Chatterbox: {startup-policy: LAZY}
      Whisper: {startup-policy: LAZY}
      Vision: {startup-policy: LAZY}
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
| `GET /api/v1/mcp/status`, `POST /api/v1/mcp/{serverId}/connect`, `/disconnect` | MCP server diagnostics and lifecycle controls |
| `GET /api/v1/cognitive-graph`, `/debug` | Cognitive event graph for the diagnostics UI |
| `GET /api/v1/debug/*`, `/api/v1/research/requests/{id}` | Pipeline/request/tool-loop debug snapshots |
| `POST /api/v1/router/analyze`, `/compare` | Brain-routing decision inspection |

### Coding Agent MVP

Jarvis includes a controlled Coding Agent foundation exposed under `POST/GET /api/v1/coding/*` and surfaced in the Windows client's `Kod` tab. Workspaces have an explicit execution host:

- `WINDOWS`: the default for workspaces added from the Windows UI. Core stores the Windows path as an opaque string and delegates filesystem, Git and process operations through the existing Windows WebSocket bridge using `CODING_EXECUTOR_REQUEST`. Core must not resolve `D:\...` with `Path.of(...)`, `Files.*`, or local `ProcessBuilder` on Ubuntu for these workspaces.
- `SERVER`: an explicit Core-local workspace for server-side paths. These continue to use canonical local filesystem validation and local bounded command execution.

The Windows Coding Executor validates canonical paths on Windows before every operation, rejects traversal outside the workspace, follows real paths to catch symlink/junction escapes, blocks destructive commands, applies command timeouts/output limits, and returns structured results over the same bridge response/correlation mechanism used by Windows-hosted MCP servers. Roblox MCP remains a separate `serverId=roblox` flow; Coding uses `serverId=coding` and is not hardcoded to Roblox.

Key endpoints:

| Path | Purpose |
|---|---|
| `GET /api/v1/coding/workspaces` | List registered coding workspaces |
| `POST /api/v1/coding/workspaces` | Register a workspace; Windows UI sends `host=WINDOWS` by default |
| `GET /api/v1/coding/workspaces/{id}/files` | List files below a workspace path |
| `GET /api/v1/coding/workspaces/{id}/files/read` | Read a whole file or line range |
| `POST /api/v1/coding/workspaces/{id}/files/search` | Search workspace text with literal or regex matching |
| `POST /api/v1/coding/workspaces/{id}/files/write` | Create or replace a file when autonomy allows writes |
| `POST /api/v1/coding/workspaces/{id}/files/patch` | Replace an expected text fragment; conflicts if it no longer exists |
| `POST /api/v1/coding/workspaces/{id}/directories` | Create a directory inside the workspace |
| `POST /api/v1/coding/workspaces/{id}/files/move` | Move a file inside the workspace |
| `POST /api/v1/coding/workspaces/{id}/files/delete` | Delete after explicit approval |
| `POST /api/v1/coding/workspaces/{id}/commands` | Run a bounded command in the workspace |
| `GET /api/v1/coding/workspaces/{id}/git` | Return branch, HEAD, status and diff |
| `GET /api/v1/coding/workspaces/{id}/build` | Detect build systems and default build command |
| `POST /api/v1/coding/workspaces/{id}/build/run` | Run the detected or requested build command |
| `POST /api/v1/coding/workspaces/{id}/tests/run` | Run the detected or requested test command |
| `POST /api/v1/coding/tasks` | Create a persisted task record with an initial plan |

Autonomy levels:

- `READ_ONLY`: file writes and arbitrary command execution are blocked.
- `ASK_BEFORE_WRITE`: UI/user initiated writes are accepted as explicit approval; destructive commands remain blocked.
- `AUTONOMOUS_IN_WORKSPACE`: read, write, patch and bounded command execution are allowed inside the workspace; destructive Git and deletion-style commands are still blocked.

Build detection prefers project wrappers where present: `mvnw.cmd`/`mvnw`, `gradlew.bat`/`gradlew`, then Maven, Gradle, npm, pnpm, yarn, Cargo and pytest conventions. Commands are run with timeout and output size limits on the workspace host. Git commit, push, merge, rebase, checkout, reset and clean are intentionally blocked by this API and must be handled through a future explicit approval flow.

Manual Windows verification scenario:

1. Start Core and the Windows client.
2. Open the left drawer and select `Kod`.
3. Enter a workspace name and an existing Windows project path such as `D:\JARVIS CODING`, then choose `ASK_BEFORE_WRITE` or `AUTONOMOUS_IN_WORKSPACE`.
4. Click `Dodaj`; verify detected build systems, branch, status and default build command are shown.
5. Click `Git diff`; verify real status/diff is displayed.
6. Click `Test`; verify the configured build/test command runs in that project and stdout/stderr/exit code are shown.
7. Refresh the client; registered workspaces are visible for the lifetime of the Core process.

Known MVP limits: the full model-driven edit-build-fix loop is not yet wired into the chat pipeline, workspace persistence is in-memory, and rollback/commit approval flows are not yet implemented. The bridge protocol already supports `command_start`, `command_poll`, and `command_cancel`; the current REST command endpoint returns the completed bounded command result. The API and UI avoid claiming unverified success: command results include real exit codes and captured output.

## Core Features

### Brain Routing & Model Selection

`RuleBasedBrainRouter` (`jarvis-brain-router`) picks a logical `Brain` (`FAST`/`REASONING`/`CLASSIFIER`) and reasoning level (`LOW`/`MEDIUM`/`HIGH`) per request based on message complexity. Independently, `ActiveModelService`/`ModelController` expose the live set of Ollama-installed models (with detected capabilities: `TEXT`, `VISION`, `TOOLS`, `THINKING`, `JSON`) and let a client switch which model is currently active (`GET /api/models`, `POST /api/models/active`) - the switch takes effect for the next request, never mid-flight.

`HeuristicComplexityAnalyzer` scores complexity from more than message length: attachment count now contributes too (`+min(4, count+1)`, capped so a single casual photo question is never automatically maximal), on top of the existing task-type/keyword signals - a short one-sentence request carrying two photos to extract and schedule previously scored complexity=1 ("Low complexity", `FAST`/`LOW`) purely because nothing looked at attachments at all. `RuleBasedTaskAnalyzer`'s creation-verb (`stworz`/`napisz`/`wygeneruj`/`przygotuj`/`zaplanuj`/`zorganizuj`) and content-noun (`plan`/`document`/`grafik`/`harmonogram`/`raport`/`trasa`/...) keyword sets were broadened generically - a specific request phrase is never hardcoded, only whole verb/noun categories, so this generalizes to any similarly-shaped "prepare/plan/organize a schedule/route/report" request, not one exact reproduction sentence.

### Active Model Persistence & Startup Warmup

`ActiveModelService` (`DefaultActiveModelService`, `jarvis-ollama`) is the **single source of truth** for which Ollama model is currently active - every consumer (chat requests, brain/model routing, `GET /api/models`, and startup warmup below) reads the exact same `activeModel()` value, never a second independently-resolved one. At startup (`afterPropertiesSet`, guaranteed by Spring to run before any `ApplicationRunner`), it resolves, in order: the model persisted in `jarvis.model.active-model-file` (default `config/active-model.txt`) if still installed in Ollama -> the configured default (`jarvis.ollama.model`) if installed -> the first installed model -> the configured default again if Ollama itself is unreachable. The result is logged once as `[JARVIS] [MODEL] Active model resolved at startup: model=... persisted=... installedCount=...`. A runtime switch (`POST /api/models/active` -> `switchTo`) validates the requested model against Ollama's live installed list, updates the in-memory active model immediately, and persists it back to `active-model-file` - so a restart resumes on whatever was last active, not the configured default.

**Startup warmup targets exactly that resolved model - never a hardcoded one.** `OllamaStartupModelWarmup` used to iterate only `jarvis.model-startup.models` and eagerly warm any entry configured `EAGER` - which defaulted to a hardcoded `gpt-oss:20b`, so a server whose active model had been switched to something else (e.g. `gemma4:26b`) still cold-loaded `gpt-oss:20b` into Ollama memory at every restart for no reason, on top of the real active model's own (uncounted, unwarmed) cold start on its first real request. `OllamaStartupModelWarmup` now depends on `ActiveModelService` directly and warms `activeModelService.activeModel()` unconditionally and exactly once, first, regardless of whether that model has any entry in `jarvis.model-startup.models` at all - logged as:

```text
[MODEL WARMUP] source=ACTIVE_MODEL model=gemma4:26b loadMs=... warmupMs=... status=READY
```

`jarvis.model-startup.models` no longer carries any main-LLM default at all (`gpt-oss:20b -> EAGER` was removed) - it exists **only** for secondary, provider-independent models (`Chatterbox`/`Whisper`/`Vision`), which keep their `startupPolicy=LAZY` default completely unchanged by this fix (never eagerly loaded, regardless of which model is active or how many times it changes). If a configured entry happens to name the currently-active model, the main warmup loop below skips it (already warmed above as the active model, logged `source=CONFIGURED` for every other genuinely-configured `EAGER` entry) so it is never warmed twice under two different code paths. `ModelWarmupRegistry.eagerModelReady` treats a model with **no** configured policy entry (the now-typical case for the active model) the same as `EAGER` for its own "was this actually warmed" diagnostic, since the active model warms unconditionally either way - an explicit `LAZY` entry still means "not warmed", respecting a deliberate override.

### Generic Goal-Completion Guard (Bootstrap-Only Answer Rejection)

The native tool loop already had a workflow-specific completion gate for Store Audit (`StoreAuditWorkflowCompletionValidator`), but nothing stopped a model from answering *any* request using only a preparatory/discovery tool result, even when that result plainly could not satisfy the request. Reported case: asked to list a connected Roblox Studio project's folders, the model called the MCP tool `list_roblox_studios` (which only reports which Studio sessions are open), got a successful result, and proposed a `FINAL_ANSWER` describing the Studio session - while its own text admitted that wasn't the folder list the user asked for. Continuing to `search_game_tree` (the tool that actually returns the folder tree) never happened.

`NativeToolLoopService`'s single `WorkflowCompletionValidator` field is now a `CompositeWorkflowCompletionValidator` chaining the existing Store Audit validator with a new, workflow-agnostic `GenericGoalCompletionValidator` - no call site in the loop changed, both gate the same two exit paths (proposed final content, and an empty model turn after tool results already exist) with the same bounded retry budget (`MAX_COMPLETION_GATE_ATTEMPTS`, the completion-recovery extension) - never a second, uncapped retry mechanism.

- **`ToolOperationRole`** (`jarvis-tools/workflow`): classifies a tool call as `DISCOVERY`, `SELECTION`, `READ`, `SEARCH`, `INSPECT`, `VERIFY`, `WRITE`, `EXECUTE`, or `UNKNOWN`. Only `DISCOVERY`/`SELECTION` are "bootstrap" (`isBootstrap()`) - a successful bootstrap call is never itself proof the user's actual goal was reached.
- **`ToolOperationClassifier`** derives the role from the tool+operation name alone via token matching (never a hardcoded per-tool/per-server catalog), so it works uniformly for native Jarvis tools and MCP-provided tools. This matters because every native MCP call is exposed as `mcp_<server>_<tool>__call` - the operation is always literally `CALL`; the real semantic action (`list_roblox_studios`, `search_game_tree`, `set_active_studio`, `inspect_instance`, `script_read`/`script_search`/`script_grep`, `execute_luau`, ...) lives entirely in the tool name, which is exactly what the classifier tokenizes and matches. An unmatched signature classifies `UNKNOWN`, deliberately treated as non-bootstrap - a classification miss can never itself block a model from finishing.
- **`GenericGoalCompletionValidator`** blocks a proposed answer only when *both* hold: every tool call that succeeded this loop classified as bootstrap-only, **and** the model's own proposed content + reasoning/thinking text admits the answer isn't actually there yet (a broad PL/EN insufficiency-phrase pattern - "to nie jest lista", "potrzebuje kolejnego narzedzia", "doesn't contain", "I only found", "need another tool", ...). It never blocks purely because the last tool was bootstrap-only: a bootstrap-only result can be a genuinely complete answer to some questions ("is a Studio session connected?"), so a legitimate short answer is never rejected. When it does block, the corrective guidance names the original user request and tells the model to call a tool that actually answers it instead of restating the bootstrap result.

See `NativeToolLoopServiceRobloxContinuationTest` for the full scripted regression (`list_roblox_studios` -> rejected premature answer -> `search_game_tree` -> real folder list accepted), plus variants: multiple open Studios (`set_active_studio` still counts as bootstrap), a genuinely empty search result (accepted immediately, no forced retry), a legitimate bootstrap-only answer (accepted immediately), persistent insufficiency admission without ever calling an answering tool (still terminates within bounded budgets - never hangs), and the map/village case where list/get-state evidence is insufficient until search/read evidence is acquired.

**Goal Contract + explicit completion verification is now wired.** `jarvis-tools/workflow/goal` (`GoalContract`, `CompletionCriterion`, `AcquiredEvidence`, `CompletionDecision`, `CompletionVerification`, `GoalCompletionVerifier`) is carried by `AgentExecutionState` inside `NativeToolLoopService`. Core creates one contract before the first real native tool call with `originalGoal=ToolCallingRequest.userMessage()`, appends compact `AcquiredEvidence` after every executed or Core-recovery tool result, and emits `[GOAL_CONTRACT_CREATED]`, `[GOAL_EVIDENCE]`, `[GOAL_COMPLETION_CHECK]`, `[AGENT_CONTINUE]`, `[AGENT_FINISH]`, and `[FINAL_SYNTHESIS] goalComplete=true` logs. `COMPLETE` is the only decision that allows final content or final synthesis; `CONTINUE` appends a compact `GOAL CONTINUATION STATUS` block and re-enters the same native loop with the full `tools[]`; `BLOCKED` is reserved for genuine no-tool/user-input/terminal failures, not ordinary retry/search/read work.

`GenericGoalCompletionValidator` remains as an early, phrase-based safety net for the specific bootstrap-only failure mode and produces corrective guidance before the structured verifier runs. It is no longer the completion authority by itself.

### Recoverable Runtime Blockers

The native loop now treats recoverable tool/runtime problems as Core-owned control flow instead of ordinary failed tool text for the model to interpret. The invariant is: the latest `Tool goal` is only the current subgoal; the root goal remains the original user request (`ToolCallingRequest.userMessage()`), and completion gates judge proposed final answers against that root goal. In diagnostics this shows up as `rootGoal`, `subgoal`, blocker/recovery reason, and evidence level logs such as `[RUNTIME_RECOVERY]`, `[RUNTIME_MODE_RECOVERY]`, `[WRITE_VERIFICATION]`, and `[GOAL_PROGRESS]`.

Core classifies failed tool results through `ToolFailureClassifier` into `RECOVERABLE`, `REQUIRES_USER`, `TERMINAL`, or `RETRYABLE_TRANSIENT`, with specific reasons such as `STALE_SESSION`, `WRONG_RUNTIME_MODE`, `TARGET_NOT_FOUND`, and `WRITE_VERIFICATION_REQUIRED`. The classifier prefers structured `errorCode`/metadata and falls back to bounded text heuristics for older MCP servers. Recovery attempts are bounded by `MAX_SAME_ACTION_RECOVERY_ATTEMPTS` and still sit inside the normal max-calls/timeout/no-progress guards.

`ConnectedRuntimeState` is the Core-owned canonical runtime identity for the current loop. For Roblox MCP, a stale `studio_id` (`SESSION_NOT_CONNECTED` / "requested studio_id is not connected") invalidates the old id, calls `mcp_roblox_list_roblox_studios`, binds the single unambiguous connected Studio as the new canonical id, retries the original action with the new `studio_id`, and only asks for clarification when discovery is not uniquely actionable. The model no longer has to be the source of truth for copying UUIDs across calls.

Mode transitions are also recoverable. If an Edit-only operation such as `multi_edit` fails while Studio is in Play, Core calls `mcp_roblox_get_studio_state`, `mcp_roblox_start_stop_play(is_start=false)`, verifies state again, retries the original write, then performs read-back verification when the write succeeds. If a Client/Server runtime tool is requested while Studio is in Edit, Core starts Play (`is_start=true`), verifies availability, and retries the original runtime inspection. These transitions are bounded and are not treated as duplicate/no-progress loops because the relevant runtime state changed.

Code edits use `WRITE -> READ-BACK VERIFICATION`: a successful Roblox `multi_edit` result means the write request was accepted, not that the bug is fixed. Core follows successful writes with `mcp_roblox_script_read` for the edited path where it can infer one. Path failures are recoverable too: a `script_read`/inspect target that returns `TARGET_NOT_FOUND` triggers `mcp_roblox_search_game_tree` using the missing basename, then retries the original read against the resolved path.

Root-cause diagnosis now distinguishes evidence levels. Console/log output is a `CLUE`; it is not a `VERIFIED_CAUSE` by itself for "why/check/debug/fix" requests. If the model tries to finalize after only console/log evidence, `NativeToolLoopService` blocks completion with `ROOT_CAUSE_NOT_VERIFIED` and requires a search/read/inspect/verify step against the referenced script, symbol, path, or runtime state before accepting the final answer. This is generic and does not encode project-specific names or the historical `MinX` example.

New scripted regressions in `NativeToolLoopServiceRobloxContinuationTest` cover stale Studio rebinding, Play -> Edit -> write -> read-back, Edit -> Client runtime inspection, missing script path rediscovery, and console clue -> script read before root-cause finalization. A real Roblox Studio MCP smoke test is still manual/environment-dependent; report it as `REAL_TEST: NOT RUN` unless a live Studio MCP session is available.

### Persistent Conversations (ETAP C/D - durable history, separate from prompt-window memory)

`working_memory` (`SQLiteWorkingMemoryStore`) is intentionally bounded - it keeps only the most recent `jarvis.memory.working-history-length` messages per conversation, trimming older ones, because it exists purely as the fast window `ConversationContextStage`/`RecentWindowConversationContextReducer` build the model's prompt context from. That trimming must never be the only place a conversation's history lives, so two new durable SQLite tables now exist alongside it and are never trimmed:

- **`conversations`** - one row per conversation: `id`, `title` (defaults to `"Nowa rozmowa"`), `created_at`, `updated_at`, `archived`, `last_model`, `rolling_summary`, `summary_until_sequence`.
- **`conversation_messages`** - the full, never-trimmed message log, indexed on `(conversation_id, sequence_number)` for deterministic ordering.
- **`conversation_summaries`** - append-only audit log of every rolling-summary regeneration (consumer: the planned rolling-summary stage), kept separate from `conversations.rolling_summary`/`summary_until_sequence` (which always hold just the *current* summary, for a fast lookup with no join).

`SQLiteConversationMemoryService` (the sole `ConversationMemoryService` implementation - every existing call site, `ConversationContextStage` and `ResponseValidationStage` alike, gets this automatically with no changes of its own) now dual-writes: every message still goes into the bounded `working_memory` window exactly as before, **and** into the durable `conversation_messages`/`conversations` tables. Deleting a conversation (`ConversationController#delete`, unchanged endpoint) cascades to all three.

**Migration is automatic, idempotent, and non-destructive** (`SQLiteMemoryInitializer`): on every startup, whatever is currently present in `working_memory` is copied forward into the durable tables with `INSERT OR IGNORE` keyed by message id - safe to run on every restart, never duplicates, never touches `working_memory` itself. Honesty note: this can only recover what is still physically present in `working_memory` at upgrade time; any message already trimmed away before this upgrade was already unrecoverable and stays that way. From the moment a Core build with this change runs once, no further loss occurs going forward.

**Conversations REST API** (`ConversationController`): `POST /api/v1/conversations` (create), `GET /api/v1/conversations` (list, most recently active first), `GET /api/v1/conversations/{id}` (metadata, `404` if unknown), `GET /api/v1/conversations/{id}/messages` (full durable history - **not** the bounded prompt window; that stays available separately at the existing `GET /api/v1/debug/conversations/{id}/context` diagnostic endpoint), `PATCH /api/v1/conversations/{id}` (partial update: `title` and/or `archived` - archiving is always reversible, never a delete), `DELETE /api/v1/conversations/{id}` (explicit, cascading, irreversible).

Not yet built: the Windows UI conversation-switcher (needs this API - next stage), the complete-turns context reducer, rolling summary generation, and the token-based context budget manager. See `SQLiteConversationRepositoryTest`, `SQLiteConversationMessageRepositoryTest`, `DurableConversationMigrationTest`, `SQLiteConversationMemoryServiceDualWriteTest`, and `ConversationControllerTest` for the regression coverage.

### AI/Tool Diagnostic Trace

> **This is a DEBUG-only feature.** When enabled it can log an entire conversation, system prompt, tool arguments, and tool results verbatim to the application log. Known secret-shaped keys (`Authorization`, `api_key`, `password`, `cookie`, `token`, ...) are redacted before logging, and binary/base64-looking values (images, other blobs) are never dumped - but ordinary conversation content, user data, and tool results **are** logged in full. Never enable this in an environment where logs are shipped somewhere untrusted, and never commit or share a log captured with it enabled without reviewing it first.

Three independent flags under `jarvis.diagnostics` (all default `false`, deliberately never tied to the separate, pre-existing `log-prompt-preview` flag):

```yaml
jarvis:
  diagnostics:
    log-full-ai-request: false   # exact outbound Ollama request JSON, post context-budgeting
    log-tool-calls: false        # model tool calls, tool-execution-begin, MCP-call-begin
    log-tool-results: false      # tool/MCP results
```

When enabled, every stage of `USER -> MODEL -> TOOL_CALL -> MCP -> TOOL_RESULT -> MODEL -> ...` is logged under the `AI_TRACE` logger as a readable, delimited block:

- **`AI REQUEST BEGIN/END`** (`log-full-ai-request`) - `OllamaProvider` logs the *exact* `String` it hands to `HttpRequest.BodyPublishers.ofString(...)`, serialized exactly once and reused for both the log and the real HTTP body - the log can never drift from what was actually sent (no separate "preview" prompt from an earlier pipeline stage). For the plain `stream()` path this is the real, post-`ContextBudgetService.fitPrompt()` prompt text, not the pre-budget one. Includes `requestId`, `model`, `endpoint`, `jobType`, `reasoningLevel`, `turn` (see below), and `payloadBytes`.
- **`MODEL TOOL CALL`** (`log-tool-calls`) - every native tool call the model generates this turn, with its exact arguments.
- **`TOOL EXECUTION BEGIN`** (`log-tool-calls`) - right before `ToolManager.execute(...)` runs, showing `source=NATIVE` or `source=MCP` and, for MCP, `mcpServer`.
- **`MCP CALL BEGIN`** (`log-tool-calls`) - at the real MCP transport boundary (`DefaultMcpServerManager#call`), showing **both** the model-facing name (`mcp_<server>_<tool>`) and the real MCP tool name actually sent to the MCP server - a mismatch between the two was a plausible root cause under investigation, so both are always shown together, never just one.
- **`TOOL RESULT`** (`log-tool-results`) - `success`/`changed`/`errorCode`/`errorMessage` plus the full result data; for MCP results this includes `content`, `structuredContent`, and `mcpServer`/`mcpTool` (already present on every MCP `ToolResult`).

**Turn correlation**: `requestId` is stable across every turn of one tool-loop execution (`InferenceDiagnosticsContext`, unchanged). The tool loop's 1-based `turn` number is threaded across the `NativeToolLoopService -> AIProvider` boundary via a thread-scoped `AiTraceTurnContext` (`jarvis-common`) rather than an `AIProvider.toolChat(...)` interface change - that would have been a much larger, riskier change for a pure observability feature. One `requestId` + increasing `turn` values is enough to reconstruct the full `AI REQUEST turn=1 -> MODEL TOOL CALL -> MCP CALL -> TOOL RESULT -> AI REQUEST turn=2 -> FINAL ANSWER` sequence from the log alone.

**Cost when disabled**: every logging call point starts with a single `volatile` boolean read (`AiTraceSettings`) and returns immediately - no pretty-printing, no redaction, no serialization happens unless the relevant flag is on.

See `AiTraceLoggerTest` (formatting/redaction/binary-omission unit tests), `OllamaProviderAiTraceTest` (proves the logged JSON is byte-identical to the real HTTP body), and `NativeToolLoopServiceAiTraceMcpIntegrationTest` (full real-MCP-path end-to-end trace, including turn correlation and the model-facing-vs-real MCP tool name).

**Native tool result messages carry the exact tool name.** Root cause of a real "no user query found in messages" HTTP 500 from Ollama `/api/chat` during multi-turn native tool continuation: `ModelMessage`/`OllamaChatMessage`'s `role=tool` message had no field for the tool name at all - the outbound JSON after a tool call carried only `content` and `tool_call_id`, never which native function (e.g. `mcp_roblox_list_roblox_studios__call`) the result belonged to. A direct, isolated test against Ollama with `tool_name` present completed multi-turn tool calling correctly, no synthetic "continue" user message required. Both DTOs now carry a `toolName`/`tool_name` field, populated from the exact `ModelToolCall.name()` the model used in the originating assistant turn (never the underlying MCP server's real tool name, never re-derived) - see `NativeToolLoopService#toolResultMessage`. Logged as `[NATIVE_TOOL_RESULT_MESSAGE]` when `log-tool-calls` is on (correlation only - `log-full-ai-request`/`log-tool-results` still own the full payload dump).

**Optional MCP argument normalization and provider tool-call repair** (see also `NativeToolSchemaMapperTest`, `NativeToolLoopServiceProviderToolRepairTest`): a model very commonly fills an optional field it has nothing to say with an empty string (e.g. `keywords=""`) instead of omitting it - schema-wise this is indistinguishable from not providing it. `NativeToolSchemaMapper` now normalizes an optional string argument's blank value to "field omitted" before validation/execution (logged as `[NATIVE_TOOL_NORMALIZATION]` when `log-tool-calls` is on), driven entirely by the runtime schema's `required` flag - never a hardcoded per-tool exception. A *required* string sent as `""` is still rejected exactly as before. Separately, if the provider itself fails to parse a native tool call (malformed/truncated arguments JSON), `NativeToolLoopService` now gives the model a small, bounded number of chances (`MAX_PROVIDER_TOOL_CALL_REPAIR_ATTEMPTS`, currently 2) to retry with the same tool definitions still available, logged as `[PROVIDER_TOOL_REPAIR] attempt=N/2`, before falling back to the existing safe-text-only recovery. A connection/timeout/availability failure is never treated as a repairable tool-call parsing problem. Neither change touches `max-calls-fast`/`max-calls-research`/`timeout-seconds`.

### Vision / Image Attachments

Chat requests may attach images (resolved through the same temporary-workspace mechanism as file attachments). `ImageAttachmentStage` loads them into the pipeline, and `ModelExecutionStage` gates the call behind the active model's detected `VISION` capability - a non-vision model never silently receives image bytes it can't use. Vision-capable requests are sent through Ollama's `/api/chat` transport (per-message `images` field), not `/api/generate`, because at least one real chat-templated multimodal model has been observed to silently ignore `/api/generate`'s top-level `images` field. When an image is attached, the prompt also carries an explicit `=== ATTACHED IMAGES ===` note, so a model reasoning strictly from prompt text doesn't talk itself into concluding no image was provided.

### Current-Message Attachments vs Knowledge Workspace

Images attached to the *current* user message and documents persisted in the *Knowledge Workspace* are two structurally separate data sources, and the model is explicitly told so:

- The current-message-attachments policy is injected into the main model's decision prompt only when the request actually has images (`MainModelIntegratedToolTrigger`), and the same rule is restated in the base identity prompt (`config/jarvis.md`): read attached images directly with your own vision; never ask a tool to fetch/load/analyze a current-message image; never use `KnowledgeTool` to locate one - it only searches persisted documents.
- Images attached to the current message travel *with* the request into the native tool loop, not just the single main-model decision call: `ModelMessage` carries an `images` field (by reference to the same `ImageAttachment`s `ImageAttachmentStage` resolved once - never copied), `ToolCallingRequest`/`ToolCallingStage` forward `PipelineContext#images()` into it, and `NativeToolLoopService` attaches them to the loop's initial user turn. Because the tool loop's message list is append-only and resent in full on every turn, the images only need to be attached once - they are still present on that same user message after any number of tool calls, with no separate multimodal-context object and no re-encoding per turn. `OllamaProvider.toolChat` forwards them through Ollama's `/api/chat` per-message `images` field, the same transport the plain (non-tool) vision path already uses. This replaced an earlier design where images were deliberately dropped once the native tool loop started (the model was expected to extract everything it needed into the `TOOL_REQUEST` goal text) - that broke down for high-cardinality extraction (e.g. 23 store rows read off a table don't fit losslessly into a short goal string), so the model would ask the user to resend images it had already read seconds earlier. See [Store Audit Dataset](#store-audit-dataset) for how extracted records are kept as structured state instead of prompt text, which is the other half of the same fix.
- **Defensive routing**: if the model still emits a `TOOL_REQUEST` whose goal reads as "fetch/retrieve/analyze the attached image" while the current message actually has images (`AttachmentRetrievalIntentDetector` - an action-word + attachment-noun match, not a fixed phrase list, so it generalizes across languages/wording without being tied to any one workflow), `ModelExecutionStage` does not hand that off to the tool loop. It re-asks the main model once, with a short internal corrective note appended to the prompt ("images are already in your multimodal context, do not use a tool to retrieve them"), and uses the corrected decision. This retry is capped at one attempt (`MAX_ATTACHMENT_ROUTING_RETRIES`) - if the model still gets it wrong, Core lets the request proceed rather than looping forever, relying on `KnowledgeTool`'s honest not-found reporting (see below) to keep things visible instead of silently retrying indefinitely. Diagnostics are logged under `[ATTACHMENT_ROUTING]` (attachment counts, retry attempts, recovery outcome - never image bytes/base64).
- `KnowledgeTool` itself is unchanged and still the correct tool whenever the user is actually asking about persisted knowledge (e.g. "sprawdz w zapisanej wiedzy...") - this mechanism only concerns the images attached to the current message.
- **The model never has to know or copy a real attachment id at all - it cites a 1-based position instead.** `ImageAttachment` carries the real `attachmentId` Core resolved it from (`ImageAttachmentStage` threads `AttachmentReference#attachmentId()` through instead of discarding it), and `ToolCallingStage` registers that same image-ordered list with `StoreAuditDatasetService#registerAttachments` before the tool loop starts. `NativeToolLoopService`'s system prompt lists a `CURRENT MESSAGE ATTACHMENTS` block (`N. attachmentId: ..., name: ..., type: image`) purely for human/log readability, but the model-facing `storeDataset` record schema's *preferred* provenance field is `sourceAttachmentIndex` - the same 1-based position (Image 1 = `1`, Image 2 = `2`, ...) - not the id string itself. `StoreAuditDatasetService` resolves `sourceAttachmentIndex` deterministically against Core's real, ordered current-message attachment list (never the model's own request, never a previous message, never conversation history) and writes the real UUID into the record's internal `sourceAttachmentId` for traceability - the model is never trusted to supply that UUID itself once an index is given. An index outside `1..N` (`N` = this message's real attachment count) rejects the *whole* call outright as `STORE_DATASET_ATTACHMENT_INDEX_INVALID`, naming the valid range - never silently mapped, guessed, or resolved against a different message's attachments. `sourceAttachmentId` still exists as a fallback field, purely for explicit typed-list input with no attachments at all (text-only extraction) or a caller that already resolved the real id itself; when both are present, the index always wins. This closes the exact production failure where a model had to copy real attachment UUIDs by hand into `sourceAttachmentIds` and got even one character wrong (`storeDataset.START_DATASET` rejected with `"unknown declared attachment ids=[...]"`) - it now only ever has to know which numbered image a record came from, a value it already sees rendered in its own context.
- **`[ATTACHMENT_PROVENANCE]` diagnostic log**, emitted once per tool-loop-entering request right before registration: `requestId`, `registeredAttachments`/`imageAttachments` (counts), `registeredIds`/`imageContextIds` (the real ids on both sides), and `mappingConsistent` (whether every image-context id is present in the registered set) - counts and real ids only, never base64/image bytes - so a future provenance mismatch can be pinned to upload, `ImageAttachmentStage`, context propagation, or the model/tool layer just from this one line.

### Conversation-Scoped Image Memory

Before this feature, an image was only ever visible to the model while handling the exact message it was uploaded with - `PipelineContext#images()` was resolved fresh per request from `ChatRequest#attachments()` alone, so a later message referring back to "the second photo" got the textual conversation history but never the pixels again, even though the file was still sitting in its temporary workspace. `ImageAttachmentStage` (`jarvis-core`) now also registers every current-message image into a durable, SQLite-backed **`ConversationImageRegistry`** (`jarvis-common`/`jarvis-memory.image`) and, on every later message of the same conversation, runs a deterministic resolver to decide whether the current text refers to one of those earlier images - re-reading and re-attaching it as native vision input (never as base64 pasted into the text prompt) when it does.

**Data model** - `ConversationImageRecord` (metadata/reference only, **never image bytes or base64** - the physical file always stays in the temporary workspace): `conversationId`, `messageId` (the uploading request's id), `sourceMessageOrdinal` (1-based, assigned once per distinct message that ever registered an image for this conversation), `ordinalInMessage`, `conversationLabel` (a stable `image-N` label), `attachmentId`, `workspaceId`, `originalFileName`, `mediaType`, `sizeBytes`, `createdAt`, `expiresAt`, and `status` - one of `AVAILABLE`, `EXPIRED` (retention window elapsed), `MISSING` (a live re-read found the backing file gone before its retention window elapsed), `DELETED` (its conversation was deleted), `INVALID` (rejected at registration). The SQLite table (`conversation_images`, added to `SQLiteMemoryInitializer` alongside the existing durable conversation tables) has a unique `(conversation_id, attachment_id)` index, so re-registering the same attachment is a safe no-op - it is never possible for an image to be looked up, selected, or mutated through a `conversationId` other than the one it was registered under.

**Retention is never a proof of availability.** A stored `AVAILABLE` status only means the record has not yet been marked otherwise; every reuse re-verifies the actual file through `TemporaryWorkspaceService` first (`ImageAttachmentStage#verifyAndAttachHistoricalBytes`). If the file is gone (deleted by `TemporaryWorkspaceCleanup`'s own, independent workspace-TTL sweep, or anything else), the record is downgraded to `MISSING` on the spot and the image is silently dropped from what gets sent to the model - never an empty/partial base64 payload, never a failed response. A separate periodic sweep (`ConversationImageCleanup`, every 5 minutes) also proactively flips any `AVAILABLE` record whose `expiresAt` has elapsed to `EXPIRED`, but `ImageAttachmentStage` never trusts that the sweep has already run - it re-checks `expiresAt` itself on every request too. Retention is always counted from the image's original `createdAt`, never pushed forward by later reuse - referencing an old photo again does not renew its lifetime.

**Deterministic selection, never an extra LLM call.** The main model's first decision happens before it has analyzed anything, so it can never be trusted to correctly ask for an image it has not seen yet. `ConversationImageResolver` (`jarvis-memory.image`) decides purely from the current message's own text, using plain token matching (diacritic/case-normalized, one-edit typo tolerance for words 5+ characters long, no external NLP): an explicit `image-N` label, a mentioned file name, an ordinal reference ("drugie zdjęcie", "the last screenshot", "poprzedni załącznik"), optionally qualified by which earlier message it came from ("drugie zdjęcie z pierwszej wiadomości" - a message can name *several* distinct images at once, e.g. "compare X with Y", each resolved independently) - or a reference cue with nothing more specific attached: an image/screenshot noun, an **attachment/file noun** ("załącznik", "plik", "attachment", "file" - this is what "co wysłałem ci wcześniej w załączniku?" needed and previously never matched at all, since only photo/image wording was recognized), or a bare "send" verb with no object ("co wysłałem wcześniej?", "what did I send earlier?"). For a vague/general cue: exactly one available historical image is always selected automatically (never ambiguous by elimination); several candidates fall back to every image from the single most recent message that has any (bounded by the configured limits, with a single-most-recent-image retry if the whole group does not fit) under `auto-attach-mode: REFERENCED_OR_RECENT`; `REFERENCED_ONLY` never guesses when more than one candidate exists. A message with **no** reference cue at all never auto-attaches anything from history. Every current-message image is always included regardless. Duplicates between current and historical selections are removed.

**A detected reference never reaches the model unresolved.** If a reference is detected but nothing could be safely selected - every available candidate expired/is missing, or several equally plausible candidates exist and neither a single-message group nor the single-most-recent-image fallback fits the configured limit - `ImageSelectionReason` is `AMBIGUOUS_REFERENCE` (limit/mode could not resolve it) and `ModelExecutionStage`'s **deterministic conversation-image gate** (`applyConversationImageGate`, checked immediately after the existing early-response guard, before any provider call) short-circuits with an honest answer - naming the expired file and asking for a re-upload, or listing the candidate labels and asking which one - without ever calling the model. This closes the exact production regression where "co wysłałem ci wcześniej w załączniku?" matched no reference wording at all, the model received only textual metadata with no pixels, and spent several minutes reasoning about whether it could see an image it was never actually given. The gate is a no-op (returns immediately) whenever the resolver already merged a real historical image into `context.images()` - the common, successful path is completely unaffected.

**Reaches the model through the exact same path current-message images already used - no new plumbing.** The final merged (current + selected historical) image list is set once, early, on `PipelineContext#images()`; every existing consumer - the main model's own vision-capable decision call (`ModelExecutionStage`), and `ToolCallingStage`/`ToolCallingRequest#images()`/`NativeToolLoopService`'s `ModelMessage.user(request.userMessage(), request.images())` (unchanged - it already treats every image on the request uniformly, with no notion of "current" vs. "historical") - picks it up automatically. Because the tool loop's message list is append-only and resent in full every turn, a re-attached historical image persists across every subsequent tool call exactly like a current one does, with the image's bytes pinned to that one user turn and never duplicated across `messages[]`.

**`=== CONVERSATION IMAGES ===` prompt block**, appended by `PromptBuilderStage` right after the base prompt (so both the main decision call and the tool loop's base prompt see it), lists every available/expired historical image with its source message, file name, and **three deliberately separate fields, never one ambiguous `Status`**: `Storage status` (whether Core's temporary workspace still has the file at all), `Passed as native visual input in this request` (whether it was actually attached this turn), and `Model can inspect image now` (`YES` only when both of the above are true - the single field the model is told to trust). `Storage status: AVAILABLE` alone must never be read as "the model can see the pixels" - that exact conflation is what caused the production regression above even on turns where the deterministic gate did not apply. An `EXPIRED`/`MISSING`/`DELETED`/`INVALID` entry additionally carries `Required action: Ask the user to upload this image again if its contents are needed.` This text block is metadata only; the actual pixels always travel separately through `messages[].images`.

**Store Audit provenance is unaffected.** `ToolCallingStage` deliberately keeps a separate `currentMessageImageAttachmentIds` metadata key (set by `ImageAttachmentStage`) so `StoreAuditDatasetService#registerAttachments`/`sourceAttachmentIndex` provenance - and the `[ATTACHMENT_PROVENANCE]` consistency log - only ever see the *current* message's own attachments, never a historical image conversation-image-memory re-attached from an earlier one; a model must never be able to cite an old photo's position as if it were freshly uploaded with this message.

**Configuration** (`jarvis.conversation.images`, sibling of `jarvis.conversation.history`):

```yaml
jarvis:
  conversation:
    images:
      enabled: true                        # master switch - false disables cross-message reuse entirely (current-message vision is unaffected)
      retention: 60m                        # how long an image stays AVAILABLE, counted from upload
      max-active-images: 8                  # max images (current + re-attached historical) sent natively in one request
      max-total-bytes: 16777216             # max combined byte size of those images
      auto-attach-mode: REFERENCED_OR_RECENT  # or REFERENCED_ONLY - never auto-attach on a vague reference
```

All numeric/duration fields fall back to a safe default (matching the defaults above) when missing or non-positive, so a malformed configuration degrades to a working default instead of failing startup. If `jarvis.conversation.images.retention` is configured longer than `jarvis.workspace.ttl`, the image's *physical file* still disappears at the shorter workspace TTL regardless of what the conversation-image retention says - `ImageAttachmentStage` logs a one-time startup warning for this inconsistency (`conversation image retention > temporary workspace TTL`) and, as always, a live re-check on reuse (never the stale `AVAILABLE` status alone) decides what actually gets sent.

**Cost of re-sending historical images**: every historical image included in `selectedImagesForModel` is re-encoded to base64 and sent as full native vision input on that turn, exactly like a freshly uploaded one - this is real additional prompt/VRAM cost for a vision-capable model, which is precisely why selection is bounded (`max-active-images`/`max-total-bytes`) and never blanket-attaches the whole conversation's image archive on an unrelated message.

**`[CONVERSATION_IMAGES]` diagnostic log and `CognitiveEventType.CONVERSATION_IMAGES` event** (published by `ImageAttachmentStage`, only when the request actually involves any image - current, historical-available, or historical-expired; a plain text-only message never emits this), counts and identifiers only, never base64:

```text
[CONVERSATION_IMAGES] conversationId=... current=0 historicalAvailable=2 historicalExpired=1 selected=2 selectedBytes=... selectionReason=HISTORICAL_IMAGE_REFERENCE
```

The event's metadata additionally carries a per-image list (`attachmentId`, `label`, `originalFileName`, `sourceMessageOrdinal`, `status`, `expiresAt`, `passedNatively`) - the Windows client reads this to patch the status/expiry shown under the attachment chip on whichever already-sent message originally uploaded that image (see below), never removing the message from history.

**Conversation deletion** cascades to the image registry (`SQLiteConversationMemoryService#deleteConversation` now also calls `ConversationImageRegistry#deleteConversation`) - metadata only; physical file cleanup remains exclusively `TemporaryWorkspaceService`'s own TTL mechanism, never duplicated here.

**Windows client**: `AttachmentMetadata` gained `expiresAt`/`status` fields (both absent/blank by default, so an older Core build or an upload response that predates the first `CONVERSATION_IMAGES` event of the conversation renders exactly as before). `MainViewModel` reads the `CONVERSATION_IMAGES` event and patches the matching attachment entry (by `attachmentId`) on whichever `ChatMessage` already carries it - `ChatBubble` renders a small status line under the existing attachment chip ("Dostępny jeszcze przez 42 min" / "Obraz wygasł - prześlij ponownie..."), refreshed whenever a new event arrives (not a continuously-ticking countdown - deliberately scoped, since this client currently has no conversation-history-reload feature to refresh against on its own; see Known Limitations).

**Manual verification**: start a fresh conversation; upload two different images and ask what they show; send a plain unrelated text message; then ask "wróć do drugiego obrazu i dokładniej przeanalizuj jego prawy dolny róg" - confirm in the Core logs that `[CONVERSATION_IMAGES] ... selectionReason=HISTORICAL_IMAGE_REFERENCE` fired and `[NATIVE_TOOL_LOOP]`/provider request logs show the second image's bytes going out again, and that the model actually describes the image instead of asking for a re-upload. Then either wait out `jarvis.conversation.images.retention` or restart Core with a short test value (e.g. `retention: 10s`) and ask about the same image again - confirm no base64 is sent (empty/absent `images` in the outbound request), the model is told the image expired via the prompt block, and it asks the user to re-upload instead of claiming to still see it. Finally, start a second conversation and confirm neither image is ever visible there.

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

### Model Context Protocol MCP

MCP support is integrated as a dynamic source for the existing native tool system, not as a second tool runtime. `McpServerManager` handles configured MCP server lifecycle, discovery, status, and calls; `McpDynamicToolSource` wraps discovered MCP tools as regular `JarvisTool` instances; `DefaultToolRegistry` exposes their schemas only when MCP is enabled and discovery succeeds. MCP tool names are namespaced as `mcp_<server>_<tool>` to avoid collisions with native tools.

```text
LLM
  -> Core ToolRegistry / ToolManager
  -> MCP adapter
  -> MCP server or Windows MCP bridge
  -> external app, e.g. Roblox Studio
```

With `jarvis.mcp.enabled=false`, no MCP discovery occurs and regular chat/tool behavior is unchanged. In the default dev profile Roblox MCP is enabled through the Windows bridge, so Core waits for the Windows UI to register the bridge before it can discover Roblox tools. `CONNECTED` alone never implies tools were actually discovered - `DefaultMcpServerManager` tracks discovery as its own phase, retries a genuinely empty result a bounded number of times with backoff, and a manual reconnect (`POST /api/v1/mcp/{serverId}/connect`) always clears that bound for a fresh attempt. On the Windows side, `WindowsMcpProcessClient` kills the *entire* process tree it spawned (not just the immediate `cmd.exe` child) on close/reconnect, and verifies the underlying OS process is still alive before ever reusing it. A real tool call can also appear to hang even after discovery works perfectly - `JarvisWebSocketHandler` used to run the whole chat pipeline synchronously inside the WebSocket message callback, and since chat and the MCP bridge share one persistent session, a long native tool loop blocked delivery of the very `MCP_BRIDGE_RESPONSE` it was waiting on (the Windows side could answer in milliseconds and Core still wouldn't see it for minutes); chat processing now runs on a dedicated executor so the session stays free to deliver bridge frames throughout. See [docs/MCP.md](docs/MCP.md) for configuration, Roblox Studio setup, status endpoints, the full discovery lifecycle, this WebSocket-serialization fix, troubleshooting, and the Windows bridge flow.

`NativeToolLoopService` drives the actual loop: it exposes the *full* tool catalog to the model on every turn (which tool to call is always the model's own decision, never Core's), executes whatever the model calls, and feeds results back until the model returns a plain-text final answer. Loop safety:

**Re-entrant by design.** `TOOL_REQUEST` is a valid action at *every* turn of this loop, not just the first one - a model that still needs a capability after several tool calls, after a `system__notify_user` status update, or during what looks like final synthesis must never have that request silently discarded. Two failure modes this specifically fixes:

- **A model writing its next tool request as plain text instead of an actual native tool call** (a formatting habit carried over from the outer `TOOL_REQUEST`/`FINAL_ANSWER`/`CLARIFICATION` JSON protocol one layer up, even though this loop already has real native tool-calling available). `detectStructuredEnvelopeType` sniffs the loop's own plain-text turn for a `{"type":"TOOL_REQUEST",...}` envelope; when found, Core pushes the turn back with a corrective system message telling the model to make the actual call, and continues the loop (bounded - `MAX_MALFORMED_CONTINUATION_ATTEMPTS`, currently 2 - after which the text is accepted as final content rather than nagging forever).
- **A tool-less "final synthesis" call** (`ToolCallingStage`'s narration turn, used only after the native loop has already reported `[FINAL_SYNTHESIS] goalComplete=true`) is narration-only. It never re-enters the tool runtime and never decides whether another tool is needed; if it emits a `TOOL_REQUEST` envelope anyway, the protocol guard converts that into an honest incomplete-workflow message instead of treating synthesis as a new planning turn.

**Workflow completion gate - on every exit path.** A plain-text final turn is not automatically "the task is done" - if this loop actually engaged with a stateful workflow (currently: a Store Audit dataset touched via `storeDataset`/`location.GEOCODE_DATASET` in this same loop), a pluggable `WorkflowCompletionValidator` (`com.jarvis.tools.workflow`) gets the final say before the content is accepted. When it reports the workflow isn't finished, Core pushes back exactly what remains (e.g. "dataset stage=GEOLOCATED, call `storeDataset.SUBMIT_SCHEDULE`") and continues the loop instead of returning - bounded by `MAX_COMPLETION_GATE_ATTEMPTS` (currently 3). Critically, this gate is consulted on **every** path that can end the loop with existing tool results, not just a genuine plain-text final answer: a native model turn with `content=""` **and** `toolCalls=[]` (no tool call, no text, but earlier results already collected) previously fell straight through to `FINAL_SYNTHESIS_REQUIRED` - handing control to `ToolCallingStage`'s tool-less narration call - without ever asking the validator, which is exactly how a Store Audit dataset stuck at `GEOLOCATED` (verification skipped, `SUBMIT_SCHEDULE` never called) once produced a "geocoding summary" as if the task were finished. That empty-response exit now runs through the identical gate/re-entry logic (see `NativeToolLoopServiceCompletionGateTest`). The agent loop itself stays generic (it only knows how to ask "is this workflow done?"); Store Audit provides the concrete implementation (`StoreAuditWorkflowCompletionValidator`) so a future stateful workflow can plug in its own without the loop changing. A dataset merely *existing* for the conversation (via cross-turn continuity) never triggers this - only a loop that actually touched it does, so an unrelated later turn is never blocked by someone else's in-progress dataset. When the loop gives up on a genuinely incomplete workflow (timeout or call-budget exhaustion, bounded retries used up), the honest fallback answer names the exact stage the dataset was left at instead of a generic apology - it never lets that silence imply the task finished.

**A failed `CREATE_DATASET`/`START_DATASET` attempt is never mistaken for "workflow complete".** Both operations take no `datasetId` argument to fall back on, so a rejected call (bad provenance, an out-of-range `sourceAttachmentIndex`, ...) leaves `activeDatasetId` blank for this loop even though the workflow was genuinely engaged - the gate's own `!datasetTouchedThisLoop() || activeDatasetId.isBlank()` shortcut for "nothing to gate on" used to trigger on exactly that blank id, so a rejected creation call followed by an empty model turn was accepted as a finished task with no dataset at all. `NativeToolLoopService` now captures whether the last `CREATE_DATASET`/`START_DATASET` attempt this loop failed (`datasetCreationAttemptFailed`) and that call's own rejection message, threading both into `WorkflowCompletionContext`; `StoreAuditWorkflowCompletionValidator.assess` checks that combination (`datasetCreationFailed && activeDatasetId.isBlank()`) *before* the "nothing to gate on" shortcut and reports `STORE_AUDIT_DATASET_CREATION_FAILED`, with the exact rejection reason in the guidance pushed back to the model - so the loop keeps going and asks for a corrected retry instead of silently accepting nothing was created. A later, successful creation call (or a duplicate-source rejection returning a pre-existing dataset) clears this immediately and falls through to the normal stage-based checks, since `activeDatasetId` is no longer blank at that point.

**Canonical `datasetId` is Core-owned workflow identity - the model is never its source of truth.** A production run showed the model successfully creating a dataset, then on the very next call inventing a plausible-looking but entirely fictitious `datasetId` (e.g. `dataset_0_0_1724357613666_1724357613666`) for `APPEND_RECORDS`. That call failed as expected, but `NativeToolLoopService`'s own `activeDatasetId` update logic used to fall back to `action.arguments().get("datasetId")` - the model's own unverified argument - whenever a dataset-touching call failed, so the FAILED call's invented id silently overwrote the loop's canonical workflow state. The next turn's completion-gate check then looked up that fictitious id, found nothing, read back `stage=n/a`, and (via the "unknown/expired dataset - nothing to gate on" shortcut in `StoreAuditWorkflowCompletionValidator`) reported `complete=true` - even though the real dataset was still sitting at `BUILDING` with 8/23 records. Two fixes, both in `NativeToolLoopService`:

- **`activeDatasetId` only ever moves forward from a Core-confirmed successful result** (`result.data().get("datasetId")` on `result.success()==true`) - a failed call's `action.arguments()` can never write to it, no matter what the model supplied.
- **Every dataset-referencing call (`storeDataset.APPEND_RECORDS`/`FINALIZE_DATASET`/`VERIFY_DATASET`/`GET_DATASET`/`SUBMIT_SCHEDULE`, `location.GEOCODE_DATASET` - everything except `CREATE_DATASET`/`START_DATASET`, which create a new dataset rather than reference an existing one) is resolved against the loop's active canonical dataset *before* it ever reaches the real tool.** While an active dataset exists for this loop: a missing/blank `datasetId` argument is filled in automatically with the canonical id (the model never has to remember or repeat a UUID it was already given - the same principle already applied to `sourceAttachmentIndex`); a supplied id matching the canonical one passes through unchanged; a supplied id that does **not** match is rejected outright as `STORE_DATASET_ID_MISMATCH`, without ever calling the real dataset service/tool at all - so a hallucinated id can never waste several real tool calls (or, worse, silently succeed against the wrong dataset) before the model notices. The rejection's `data` names the exact canonical `activeDatasetId`, current `stage`/`count`/`expectedRecordCount`, and `nextRequiredAction`, and the compact `ACTIVE WORKFLOW: STORE_AUDIT` status block (now including `CANONICAL DATASET ID`) is pushed back on the same turn, so the model can self-correct on the very next call instead of the loop burning a fixed number of pointless retries. Every rejection is also logged as `[STORE_AUDIT_DATASET_CONTEXT] activeDatasetId=... suppliedDatasetId=... operation=... match=false action=REJECT_AND_PRESERVE_ACTIVE`.

This does **not** disable explicit `datasetId` use in general - `datasetId` is still a normal (now optional-in-schema) argument on every one of these operations, so a standalone/administrative call made before any Store Audit workflow is active in this loop (e.g. `GET_DATASET` on a specific known id, with no dataset yet touched this loop) is untouched by this resolution and works exactly as before; the mismatch check only ever applies once an active canonical dataset genuinely exists for this loop.

**Required-document gate - a hard precondition, not just a soft completion check.** A `WorkflowCompletionValidator` can declare `requiredDocumentPath()` - the logical Knowledge Workspace path of a document its workflow needs read before progressing (Store Audit: `Work/Scheduling/StoreAuditScheduleWorkflow.md`, the authoritative planning procedure). The loop itself never hardcodes any path - it only tracks whether a successful `knowledge__read_document` call this turn matched whatever the active validator asked for, and reports that back through `WorkflowCompletionContext#requiredDocumentLoaded()`. Store Audit's validator treats a dataset at `LOCKED`/`GEOLOCATED` as *not complete* until that flag is set, with guidance to call `knowledge__read_document` before geocoding/planning - so the model can never reach a final schedule by improvising grouping rules it was never shown. Beyond that completion-gate check (which only ever runs when the model presents a final answer), `NativeToolLoopService` also enforces this as a **hard precondition on the `GEOCODE_DATASET` call itself**: a `LOCKED` dataset with the document not yet read blocks the call immediately (`errorCode=STORE_AUDIT_WORKFLOW_DOCUMENT_NOT_LOADED`, `data.requiredDocumentPath` naming the exact path) *before* the real `LocationTool`/geocoding provider is ever invoked - a model attempting geocoding early can never waste a real geocoding call, and self-corrects on the very next turn instead of only being told "not complete" after presenting a premature final answer. This mechanism is deliberately generic/extensible: a future stateful workflow declares its own required document without any loop change, and the document's full text is never duplicated into the system prompt - only the read-or-not-yet-read boolean matters to the gate.

**One deterministic source of truth for "what's next".** `StoreAuditWorkflowCompletionValidator.nextRequiredAction(DatasetStage, workflowDocumentLoaded)` is a single static method mapping stage (+ document-loaded state, relevant only at `LOCKED`) to the next required operation name - `APPEND_RECORDS`, `VERIFY_DATASET`, `READ_REQUIRED_WORKFLOW_DOCUMENT`, `GEOCODE_DATASET`, or `SUBMIT_SCHEDULE` (blank once `SCHEDULED`). Every place that needs to tell the model what comes next - the compact workflow status block below, completion-gate guidance, stage-guard rejections, the bounded recovery guidance - calls this one method, so there is never more than one implementation of the Store Audit state machine's ordering to keep in sync.

**Hard stage guards - Core enforces the pipeline order itself, never only a prose instruction.** `StoreAuditDatasetService.submitSchedule` rejects outright (`errorCode=STORE_AUDIT_INVALID_STAGE`, `data.stage`/`data.requiredNextAction`) unless the dataset is already `GEOLOCATED` or `SCHEDULED` - a schedule can never be accepted against un-geocoded records just because the record-id coverage invariant happens to be satisfiable (the exact production gap: `SUBMIT_SCHEDULE` used to only reject `BUILDING`, so a still-`EXTRACTED` or `LOCKED`-but-never-geolocated dataset could be scheduled). `GEOCODE_DATASET` already rejected anything before `LOCKED`/`GEOLOCATED` (`STORE_DATASET_NOT_VERIFIED`, enforced independently in both `LocationTool` and `StoreAuditDatasetService.updateGeolocation`). Together with the required-document gate above, every edge the state machine forbids - `EXTRACTED`→`GEOCODE_DATASET`, `EXTRACTED`→`SUBMIT_SCHEDULE`, `LOCKED`→`SUBMIT_SCHEDULE`, `LOCKED`→`GEOCODE_DATASET` without the document - is rejected immediately, before any further payload validation, never left to the model remembering the order from the system prompt.

**State-aware `GET_DATASET` no-progress guard.** A model that hits a rejection (e.g. a failed `VERIFY_DATASET` pass) previously sometimes spiraled into calling `GET_DATASET` repeatedly - in one production run, roughly ten consecutive `GET_DATASET` calls, each a full inference turn, against a dataset that had not changed at all since the first call. `NativeToolLoopService` now snapshots a compact state signature (stage, record count, verified/geolocated counts, schedule size) after every real `GET_DATASET` call; a repeated `GET_DATASET` against the *same* dataset with an *identical* signature is short-circuited into a compact `STORE_DATASET_GET_NO_PROGRESS` result (current stage + next required action) instead of dumping the full dataset again. This is genuinely state-aware, not "`GET_DATASET` allowed only once" - the signature is recomputed fresh every time, so it simply no longer matches (and the call is allowed through normally) the moment anything actually changes (an append, a verification, geolocation, a schedule submission), with no separate invalidation step needed.

**Bounded completion-recovery budget.** The completion gate could correctly decide the workflow was incomplete and that it should re-enter the loop, but the *outer* step budget had often already been exhausted at that exact moment - the `for (step <= maxCalls)` loop's own bound ended the loop right after the re-entry `continue`, so the decision to recover never actually got a turn to run in (logged as `REENTER_TOOL_LOOP` immediately followed by `TOOL_LOOP_END`, with the workflow still incomplete). The first time this happens in a given loop, `maxCalls` is bumped by `MAX_COMPLETION_RECOVERY_EXTENSIONS` (currently 5) turns *in one shot* - not incrementally one at a time, since a genuine recovery from an early stage can legitimately need several consecutive operations in a row (verify, read the required document, geocode, submit) and a single bonus turn would only ever cover the first of those. Granted at most once per loop (bounded, never unbounded). That bonus turn's guidance is also more tightly constrained than the normal completion-gate message - it names the exact current stage, the exact next required action, and (for `VERIFY_DATASET`/`SUBMIT_SCHEDULE`) the valid `recordIndex`/`storeIndexes` range, and explicitly tells the model not to call `GET_DATASET` again unless the dataset actually changed - a recovery turn is meant to act directly on the known next step, not re-explore state it already has.

**Compact workflow status, refreshed after every dataset-touching call.** The model's understanding of the task must not collapse to whatever narrow immediate tool goal a `TOOL_REQUEST` happened to carry (e.g. `storeDataset.START_DATASET(sourceImageCount=2)`) after the first tool call. Rather than re-sending the full 30-40k character system prompt every turn, `NativeToolLoopService` appends a small, repeatable status block as a system message right after any `storeDataset`/`GEOCODE_DATASET` call:

```text
ACTIVE WORKFLOW: STORE_AUDIT
USER GOAL: przygotuj grafik na sierpien
CANONICAL DATASET ID: 5c921337-eef6-4e27-85be-b7dca62fdedd
REQUIRED TERMINAL STATE: SCHEDULED
CURRENT STATE: LOCKED (23 record(s))
REQUIRED WORKFLOW DOCUMENT LOADED: false
NEXT REQUIRED ACTION: READ_REQUIRED_WORKFLOW_DOCUMENT
```

Every re-entry/gate decision is logged for one requestId as `[AGENT_LOOP] turn=N action=TOOL_REQUEST|FINAL_CONTENT|FINAL_ANSWER`, `[WORKFLOW_STATE] workflow=STORE_AUDIT datasetId=... stage=... records=... expectedRecords=... workflowLoaded=...`, `[COMPLETION_GATE] workflow=STORE_AUDIT stage=... complete=false nextRequiredAction=...`, `[NATIVE_TOOL_LOOP] ... REENTER_TOOL_LOOP reason=...`, and `[NATIVE_TOOL_LOOP] ... COMPLETION_RECOVERY_BUDGET_EXTENDED maxCalls=... extensionsGranted=...`.

**Tool-call budget raised from real loop state, not just the upfront intent guess.** `ToolIntent` (below) is a lightweight upfront classifier and can miss a Store Audit task entirely - the reported production case ("przygotuj grafik na sierpien") resolved to `NO_TOOL` since neither the user message nor the model's own narrow tool goal contained any geolocation-flavored keyword, capping the loop at the low default budget. Two independent, state-based signals now raise the floor to `stateful-workflow-min-tool-budget` (default 20) instead: an **existing dataset already found for the conversation** at loop start (cross-turn continuation), and, dynamically, **the first time any `storeDataset` operation actually executes** during the loop - the budget is bumped immediately after that single call, before the rest of a full extraction/verify/geocode/plan/submit pass has had a chance to exhaust the original low budget. Neither signal is a keyword match on the user's wording; both come from real tool-call/state observations, so this generalizes to any phrasing without hardcoding a specific request shape.

**A model turn with neither a tool call nor any text content** (observed in production: a large multimodal + many-native-tool prompt the model briefly produced nothing for, after tens of seconds of "thinking") previously failed the whole task immediately on the very first empty turn, surfacing as "Nie udalo mi sie teraz zebrac wystarczajacych danych: EMPTY_MODEL_RESPONSE_WITHOUT_TOOL_CALL". This is now retried with an explicit corrective system message (`MAX_EMPTY_RESPONSE_RETRIES`, currently 2) before falling back to that same honest failure message - most transient empty turns now self-recover within the same request instead of failing outright.

Further loop safety:

- **Exact-duplicate blocking**: an identical `tool.OPERATION` call with identical arguments is blocked immediately (no re-execution).
- **No-progress guard**: the same `tool.OPERATION` called repeatedly with *different* arguments (e.g. a rewording of the same query) is blocked once it exceeds `max-consecutive-operation-repeats` (default 5), with a message telling the model to try something else or answer with what it already has.
- **No-native-tool-call-progress guard**: `max-consecutive-no-tool-progress-turns` (default 2) consecutive `action=FINAL_CONTENT` turns with zero tool calls stop the loop outright (`NO_NATIVE_TOOL_CALL_PROGRESS`) instead of exhausting the full turn/timeout budget - see [Store Audit Intent Routing & Tool Scope](#store-audit-intent-routing--tool-scope).
- **Call budget**: `max-calls-fast`/`max-calls-research` (default 8/15) cap total tool calls per turn, bumped further to 12 for `SEARCH_WEB`/`LOCATION`-flavored requests, and to `stateful-workflow-min-tool-budget` (default 20) once a stateful workflow is detected from real state - see the tool-call-budget paragraph above. A genuinely multi-stage task (e.g. Store Audit: create dataset, verify, read workflow document, geocode, retry, route, optimize, submit schedule, retry, final answer) easily needs well over a dozen turns - too low a budget forces the loop to cut off mid-task, which previously surfaced as a rushed/malformed final tool call once the model ran out of room.
- **Hard timeout**: `timeout-seconds` (default 600s) bounds the whole loop regardless of call count - raised alongside the call budget, since more turns need more wall-clock time.
- **Failed tool results never become the answer**: a result from a rejected/invalid call (bad arguments, a duplicate, a no-progress block) carries an internal diagnostic message (e.g. `invalidResult`'s literal `"Invalid native tool call"`) - `ToolCallingStage.fallbackToolAnswer` only ever considers `success()` results when picking fallback text, so an internal error message can never leak into the chat as if it were the assistant's real answer. Invalid native tool calls are also now logged (`[NATIVE_TOOL_LOOP] ... invalid native tool call name=... arguments=... error=...`) with the exact call that failed, for diagnosing why.
- **Redundant attachment-retrieval recovery**: a `TOOL_REQUEST` asking to fetch/analyze a current-message image never reaches the tool loop at all - see [Current-Message Attachments vs Knowledge Workspace](#current-message-attachments-vs-knowledge-workspace) for the one-retry recovery that happens one layer up, in `ModelExecutionStage`, before any tool is selected.
- **No raw JSON scaffolding ever reaches the user**: if the loop's plain-text final turn parses as a `{"type":"TOOL_REQUEST",...}` envelope (the model writing out of habit the JSON protocol it was taught to use one layer up, usually with a prose preamble in front of it - `extractJson` strips that prose and still parses it), that is never legitimate final content. `ToolCallingStage.parsedStructuredToolAnswer` returns an honest fallback message instead of the raw text in that case; `FINAL_ANSWER`/`CLARIFICATION` envelopes still unwrap to their real answer/question text as before.
  - **Live-streamed answers, not just buffered ones.** `ToolCallingStage.handleToolAnswerToken` (the live per-token narration path, distinct from the buffered path above) used to decide "structured vs. plain text" the instant a token didn't start with `{` - so a structured envelope preceded by any prose at all (`"Odczytuje dane...\n\n{"type":"TOOL_REQUEST",...}"`) streamed straight to the user as raw text before the decision could ever be corrected, since the mode is only ever decided once per turn. It now also scans for a bounded, later-arriving `{"type":...` opening (`STRUCTURED_ENVELOPE_HINT`/`STRUCTURED_DETECTION_PROBE_CHARS`) before committing to "plain text", and discards any detected prose prefix before feeding the envelope onward - a short, bounded delay for genuinely plain answers under that probe window, in exchange for a structured envelope with a prose preamble never being live-streamed as raw JSON either.
  - **Final Protocol Guard - the last, unconditional check.** `ToolCallingStage.finalProtocolGuard` runs immediately before this stage's answer becomes the pipeline response, on *every* call regardless of which path produced it - reusing the same `MainModelActionParser` the paths above already use. A `FINAL_ANSWER`/`CLARIFICATION` envelope that slipped through unwrapped is unwrapped here too; a `TOOL_REQUEST` envelope is replaced with an honest, plain-text explanation of what remains unfinished (naming the exact Store Audit dataset stage via `findLatestForConversation` when one exists for the conversation) - never the raw protocol JSON. This is defense-in-depth for the returned/stored response value on top of the streaming-level fix above, not a replacement for it.

`ToolIntent` (`jarvis-tools/.../runtime/ToolIntent.java`) is a lightweight classifier (`DefaultToolIntentDetector`) used to tune the call budget and freshness heuristics. For every value except `STORE_AUDIT` it stays advisory-only and never narrows which tools the model is allowed to see or call - see [Store Audit Intent Routing & Tool Scope](#store-audit-intent-routing--tool-scope) below for the one deliberate exception.

**Nested native tool JSON schema, not just flat strings.** `NativeToolSchemaMapper` used to collapse every argument type that wasn't `number`/`integer`/`boolean` down to a bare `"type":"string"` - including array and object arguments like `storeDataset.records` or `sourceAttachmentIds`. The model's native tool-calling grammar therefore never saw a real array/object shape for those arguments, which was the actual root cause behind a long-standing intermittent failure where the model called `storeDataset.CREATE_DATASET` with a genuinely empty `records` array despite its own reasoning describing an intent to populate it. `ToolArgumentDefinition` now carries an optional `ToolJsonSchema` (`jarvis-tools/.../schema/ToolJsonSchema.java`) - a small recursive JSON-Schema node supporting `string`/`integer`/`number`/`boolean`/`array` (with a real `items` schema)/`object` (with real `properties`/`required`) - and `NativeToolSchemaMapper` recurses into it when building each operation's native parameters, so e.g. `records` is now advertised as an array of objects with `fullAddress`/`sourceAttachmentId` (etc.) properties, not a flat string. Tools that only need a primitive type keep using the original 4-arg `ToolArgumentDefinition(name, type, required, description)` constructor unchanged (its schema is derived automatically); a tool with an array/object argument uses the 3-arg `ToolArgumentDefinition(name, required, schema)` form with an explicit `ToolJsonSchema.arrayOf(...)`/`ToolJsonSchema.object(...)` instead - every existing tool call site keeps compiling either way. Every tool with an array/object argument (`storeDataset`'s `records`/`sourceAttachmentIds`/`verifications`/`days`, `location`'s `queries`/`points`/`stops`/`recordIds` on `GEOCODE_DATASET`, `knowledge`'s `changes`) was audited and fixed the same way.

**Argument-type validation at the native-tool-call boundary.** A model sending the wrong runtime shape for a declared `array`/`object` argument (e.g. a JSON-encoded string where an array was expected) is now rejected immediately, before it is ever mapped to a `ToolAction` - `NativeToolSchemaMapper.toAction` checks the actual argument value against the operation's declared schema and throws `InvalidToolArgumentException` with a precise message (e.g. `"Argument 'records' must be an array of objects, but received string."`) instead of letting it silently coerce to an empty list several classes downstream in a tool's own argument-parsing helpers. `NativeToolLoopService` surfaces this to the model as a `ToolResult` with `errorCode=INVALID_TOOL_ARGUMENT` (vs the generic `INVALID_TOOL_CALL` for an unknown tool/operation name), so the model can react and correct itself instead of the call failing opaquely as `submitted=0`.

**`[NATIVE_TOOL_CALL]` diagnostic logging** is emitted for every raw model tool call before it is mapped to a `ToolAction`, so the exact name/argument shape the model sent is visible in server logs even when a validation or mapping error immediately follows it. Arrays are logged as size + a short bounded preview (not a full dump), since a Store Audit dataset call can carry dozens of records.

Diagnostics: `[AGENT_CONTEXT]` logs the multimodal/dataset state a tool loop starts with (image count, an existing dataset for the conversation if found, capability flags). `[AGENT_CONTEXT_CONTINUITY]` logs the canonical dataset's record count immediately before and after every `storeDataset` call, so a silent drift is visible in the logs even when nothing else fails.

### Store Audit Intent Routing & Tool Scope

A production run of a Store Audit scheduling request against GPT-OSS 20B (23 Stokrotka/Biedronka stores, "wolałbym robić audyty równomiernie przez cały miesiąc we wtorki i środy, startujemy z Nowej Woli 05-500") never executed a single native tool call across 30 model turns and terminated with `MAX_TURNS_REACHED`, even though the main model had already returned a correct `TOOL_REQUEST`. Two independent, compounding bugs in `NativeToolLoopService`/`InformationFreshnessEvaluator`, plus two related gaps, are fixed here:

- **Word-boundary regex bug in `resolveIntent()`.** The location-keyword pattern wrapped every stem in `\b(...)\b`, requiring the match to be the *entire* word - `geocod` matched only the literal word `"geocod"`, never `"geocode"`/`"geocoding"`; `route` never matched `"routes"`/`"routing"`. The exact reported goal ("...geocode addresses, plan routes, submit schedule...") silently fell through to `NO_TOOL` because of this. Fixed by keeping only the *leading* `\b` (a real word boundary at the start, no requirement that the match consume the whole word) and listing `routes?|routing` explicitly instead of a bare `rout` stem (which would have falsely matched unrelated words like `"routine"`).
- **Explicit Store Audit workflow recognition**, factored out of the growing keyword regex rather than folded into it (`NativeToolLoopService#isStoreAuditWorkflow`): resolves to `ToolIntent.STORE_AUDIT` when (a) a dataset already exists for this conversation (real cross-turn state, never a keyword guess), (b) the goal/reason literally names the `storeDataset` tool family, or (c) an audit/schedule word (`audyt`/`audit`) and a grafik/harmonogram word appear together - deliberately never a bare `audyt`/`grafik`/`harmonogram`/`schedule` alone, since several existing regression tests intentionally use generic goal text like `"Create the Store Audit dataset."` with a dumb `NO_TOOL`-returning stub intent detector to prove the loop still recovers purely from real dataset state. `STORE_AUDIT` gets the same `stateful-workflow-min-tool-budget` floor as `LOCATION` from turn 1, instead of only after a `storeDataset` call has already executed once.
- **Restricted native tool catalog for a confidently recognized Store Audit workflow** - the one deliberate exception to `ToolIntent` being purely advisory (see the note above). `NativeToolSchemaMapper.resolveScope` now filters the model-facing tool array to the `storedataset`/`knowledge`/`location`/`system` tool families only when `resolvedIntent == ToolIntent.STORE_AUDIT`; every other `ToolIntent` (including `LOCATION`, which a plain geocoding/routing request without Store Audit signals still resolves to) keeps getting the complete runtime catalog exactly as before. This is what stops an unrelated MCP provider's tools (Roblox, 27 of them in the reported run) from being sent alongside a task that has nothing to do with them - `ToolScopeResolution#rejectedTools()` records each excluded tool with reason `"STORE_AUDIT workflow scope: unrelated tool family"` for diagnostics.
- **`InformationFreshnessEvaluator` word-boundary bug.** `containsAny` used plain `String#contains`, so the English live-data term `"now"` matched *inside* the Polish place name `"Nowej"` (as in `"Nowej Woli"`, the exact start-point address in the report) - misclassifying a plain schedule-creation request as `freshness=MUST_BE_LIVE`. Combined with the intent bug above, this drove the loop into an unbounded "Live evidence is required" retry cycle the model could never satisfy (nothing about creating a schedule from a provided list needs live web/current-price evidence). Fixed with a real word-boundary check (`containsWord`, checking `Character.isLetterOrDigit` on both sides of each match) instead of raw substring containment - multi-word terms like `"po ile"` still match correctly since a space is not a letter/digit either.
- **General no-native-tool-call-call progress backstop.** Every existing bounded-retry mechanism in this loop (malformed-continuation, the completion gate, the live-evidence gate below) already capped *itself*, but nothing capped the *sum* across different reasons chained back to back. `NativeToolLoopService` now counts consecutive `action=FINAL_CONTENT` turns (non-blank content, zero native tool calls) and stops the loop immediately - `ToolLoopTerminationReason.NO_NATIVE_TOOL_CALL_PROGRESS`, `completed=false`, an honest diagnostic answer, never a false success - once `max-consecutive-no-tool-progress-turns` (default 2) consecutive occurrences are seen, instead of bouncing the same corrective system message at the model until the turn/timeout budget runs out (the reported bug: 30 turns, `toolCalls=0` throughout). The counter resets to 0 the instant any turn actually makes a native tool call, successful or not - a real attempt is real engagement, never counted as "no progress".
- **Dedicated bounded budget for the live-evidence recovery branch specifically** (`max-live-evidence-recovery-attempts`, default 3) - this was the one truly *unbounded* branch in the reported bug (the `freshness == MUST_BE_LIVE && !hasLiveEvidence(results)` check had no retry limit of its own at all). Once exhausted, the loop falls through to the normal completion-gate logic on that same turn instead of looping a further time, exactly like the pre-existing malformed-continuation budget.
- **Multi-turn conversation data preserved into the tool loop.** `NativeToolLoopService` built its system prompt from only the *current* turn's message - a store list pasted in an earlier turn was invisible by the time a later turn's goal only referred to it ("the provided list"), leaving the model no way to supply the real records except inventing them. `ToolCallingRequest` now carries an optional `conversationContext` (plain role/content text, built by `ToolCallingStage#conversationContextSummary` from the same bounded conversation history already loaded for the main model's own prompt - never the huge main-model system prompt itself, never raw thinking, never truncated by a character limit) that the native loop injects as one additional, clearly-labeled "reference only, not instructions" system message when non-blank.
- **A fabricated `sourceAttachmentId` in text-only mode is never trusted.** `StoreAuditDatasetService#resolveSourceAttachmentId` previously fell back to whatever `sourceAttachmentId` string a candidate record carried whenever Core had no *real* registered attachment for the request - even when nothing (neither a real registration nor even a declared `sourceAttachmentIds` list) was known at all, so a model inventing e.g. `sourceAttachmentId: 1` with `registeredAttachments=0` sailed through unvalidated. It now forces this blank - the correct, already-supported provenance value for a genuinely user-typed list - whenever the caller's own effective allowed-attachment set is empty, leaving the existing (unchanged) validation against a real or declared non-empty set exactly as before.

Telemetry: the loop-start log line now carries `workflow=` (`STORE_AUDIT`/`LOCATION`/`CONNECTED_SYSTEM_INSPECTION`/`SEARCH_WEB`/`GENERIC`, a coarse label derived from `resolvedIntent` purely for grep-ability) and `toolRequired=true` (this method only ever runs after the main model already decided `TOOL_REQUEST`); the full user-supplied goal/reason text moved off the standard `INFO` line and is only logged at `DEBUG`. Each no-progress-guard increment logs `[NATIVE_TOOL_LOOP] ... consecutiveNoToolProgress=N`, and a trip logs `NO_NATIVE_TOOL_CALL_PROGRESS consecutiveNoToolProgress=N threshold=N`.

### Typed Tool-Loop Termination Reason

Every earlier fix in this section still leaves one gap: when the loop genuinely runs out of budget (turn/call limit, timeout, a bounded retry counter exhausted) with results already collected but no accepted final text, `finalAnswer` comes back blank and `ToolCallingStage` used to hand that straight to a **tool-less** final-synthesis model turn - which itself has no way to act on anything it realizes is still missing, and could (and in production, did) emit its own `TOOL_REQUEST` envelope right back at the pipeline. That envelope was then swallowed by `finalProtocolGuard` into a fully generic apology (`"Zakonczylem prace z narzedziami, ale nie otrzymalem czytelnej tresci koncowej odpowiedzi."`) that named no limit, no progress, no error, and no next step - the diagnosis had to happen by reading server logs by hand.

`NativeToolLoopService` now attaches a structured, typed **`ToolLoopTerminationInfo`** (`jarvis-tools/.../runtime/`) to every `ToolCallingResult` it returns, built from real, already-collected loop state at the exact return point - never parsed or guessed from the model's own text:

```java
public record ToolLoopTerminationInfo(
        ToolLoopTerminationReason terminationReason, boolean completed, boolean goalSatisfied,
        int configuredMaxTurns, int usedModelTurns,
        int executedToolCalls, int successfulToolCalls, int failedToolCalls, long elapsedMs,
        String lastToolName, String lastToolOperation, String lastErrorCode, String lastErrorMessage,
        String lastModelContent, String nextRequiredAction, List<String> remainingGoalCriteria,
        boolean changesMade, boolean verificationPerformed)
```

`ToolLoopTerminationReason` is a closed enum: `COMPLETED`, `MAX_TURNS_REACHED`, `TIMEOUT`, `MAX_FAILURES_REACHED` (legacy, non-native runtime only - see `DefaultToolCallingRuntime`), `MAX_OPERATION_REPEATS_REACHED`, `EMPTY_MODEL_RESPONSE`, `PROVIDER_FAILURE`, `MCP_FAILURE`, `WAITING_FOR_APPROVAL`, `INCOMPLETE_GOAL`, `FINAL_SYNTHESIS_REQUESTED_MORE_TOOLS`, `NO_NATIVE_TOOL_CALL_PROGRESS` (see [Store Audit Intent Routing & Tool Scope](#store-audit-intent-routing--tool-scope) above), `UNKNOWN` (the backward-compatible placeholder `ToolCallingResult`'s older 4-arg constructor still produces, so every existing call site keeps compiling unchanged).

**A model turn is not a native tool call.** `executedToolCalls`/`successfulToolCalls`/`failedToolCalls` count only genuine `TOOL_CALL` steps (blocked/rejected/invalid calls excluded, matching the existing `toolCallCount` helper); `usedModelTurns`/`configuredMaxTurns` count the outer loop's `step` counter separately - one model turn can carry several native `tool_calls` in a single response. `lastToolName`/`lastToolOperation`/`lastErrorCode`/`lastErrorMessage` are read from the most recent matching step (most-recent-first scan), so a failure is still visible even when later calls in the same loop succeeded.

**A single non-terminal tool failure is never presented as the reason the loop stopped.** At the generic loop-exhausted return point, `classifyExhaustedLoopReason` picks, in order: `TIMEOUT` (the loop's own hard-timeout break), `EMPTY_MODEL_RESPONSE` (the bounded empty-turn retry budget exhausted), `MAX_OPERATION_REPEATS_REACHED` (the very last recorded step was blocked as a repeat), `MCP_FAILURE` (only when **every** executed tool call this loop was an MCP call - `mcp_`-prefixed - **and every one of them failed**, i.e. genuinely zero forward progress), else `MAX_TURNS_REACHED`. One failed MCP call followed by real successful progress still reports `MAX_TURNS_REACHED`, with the earlier failure surfaced only as diagnostic context via `lastErrorCode`/`lastErrorMessage` - never as the headline reason (see `oneFailedMcpCallFollowedByRealProgressStillReportsMaxTurnsReached` / `NativeToolLoopServiceTerminationReasonTest`).

**`ToolCallingStage` now reports the real reason directly instead of ever attempting tool-less synthesis for it.** When `finalAnswer` is blank and the termination reason is one of the "loop genuinely did not reach a verified answer" set (`MAX_TURNS_REACHED`, `TIMEOUT`, `MAX_FAILURES_REACHED`, `MAX_OPERATION_REPEATS_REACHED`, `EMPTY_MODEL_RESPONSE`, `PROVIDER_FAILURE`, `MCP_FAILURE`, `INCOMPLETE_GOAL`, `NO_NATIVE_TOOL_CALL_PROGRESS`), `streamToolFinalAnswer` builds a structured plain-text report (`buildTerminationReport`) straight from `ToolLoopTerminationInfo` and returns it - the tool-less synthesis call (and therefore its own chance to emit a stray `TOOL_REQUEST`) is skipped entirely for this case. `COMPLETED` and `WAITING_FOR_APPROVAL` are unaffected and keep the existing behavior.

**A tool-less synthesis turn that still asks for more tools is now `FINAL_SYNTHESIS_REQUESTED_MORE_TOOLS`, reported honestly.** If the synthesis call (or, defensively, the loop's own final content turn) itself parses as a `TOOL_REQUEST` envelope, `finalProtocolGuard`/`parsedStructuredToolAnswer` extract that envelope's `goal`/`reason`/`context` and build a message naming exactly what was still needed and why the loop was not resumed (the original loop's own already-spent turn/timeout budget) - never the raw JSON, never a generic apology. The loop is **not** automatically resumed by this change (out of scope - see Limitations below), but every value needed to resume it later is preserved on `ToolLoopTerminationInfo`.

**`[TOOL_LOOP_TERMINATED]` log line** - one unconditional summary per loop execution (`NativeToolLoopService.logTerminationSummary`, unaffected by `log-full-ai-request`/`log-tool-calls`/`log-tool-results`, since it carries only scalar counters and already-considered-safe short diagnostic strings, never prompt/file/image content):

```text
[TOOL_LOOP_TERMINATED] requestId=... conversationId=... reason=MAX_TURNS_REACHED completed=false goalSatisfied=false
turns=15/15 toolCalls=19 successful=17 failed=2 elapsedMs=522000 lastTool=mcp_roblox_read_script lastOperation=CALL
lastErrorCode=MCP_ERROR nextRequiredAction=... changesMade=false verificationPerformed=false
```

The same fields (minus `lastModelContent`, which can carry the model's full final-answer text and is deliberately kept out of both the log line and the wire event) are attached to the existing `TOOL_LOOP_FINISHED`/`FINAL_SYNTHESIS_STARTED` `CognitiveEvent`'s `metadata` map (`ToolLoopTerminationInfo#toMetadata()`), reusing the existing publish call sites rather than a new event type - `EventController`'s schema listing is unaffected since it already predates a curated per-event-type field catalog for these two event types. An older Windows client (or any other consumer) that does not read these new metadata keys is unaffected; a newer client that reads `terminationReason` from a Core build that predates this feature simply never finds the key and falls back to its own older behavior (see below).

**Windows client**: `MainViewModel.applyToolLoopOutcome` reads the same metadata keys off the `TOOL_LOOP_FINISHED` event (`BackendEvent.metadataBoolean`/`hasMetadata` were added alongside the existing `metadataText`/`metadataLong`/`metadataTextList` accessors) and, when present and the loop did not simply complete or pause for approval, attaches a `ToolLoopOutcome` to the streaming assistant message. `ChatBubble` then renders a dedicated `ToolLoopOutcomePanel` card (headline reason, turn/call counts, elapsed time, a done/not-done checklist for `changesMade`/`verificationPerformed`, the next required action, and a collapsible technical-details section for the last tool/operation/error) in place of the plain-text answer bubble - never inside the thinking panel, never a raw stack trace or chain-of-thought. `BackendEvent.hasMetadata("terminationReason")` gates the whole thing, so an older Core build that never sends the field leaves the client's behavior exactly as it was before this feature (the plain-text answer bubble, unchanged).

Test coverage: `NativeToolLoopServiceTerminationReasonTest` (normal completion, turn-budget exhaustion with real turn-vs-tool-call counts, timeout, repeated empty model turns, approval-required, all-MCP-calls-failed, one-MCP-failure-then-real-progress) and `ToolCallingStageTest` (structured report for `MAX_TURNS_REACHED`/`TIMEOUT`, a prior MCP error shown as background not as the main reason, `FINAL_SYNTHESIS_REQUESTED_MORE_TOOLS` naming the real goal/budget) on the Core side; `ToolLoopOutcomeTest`/`BackendEventTest`/`ChatMessageTest` on the Windows client side.

**Out of scope for this change** (see Limitations): the loop is not automatically resumed after `FINAL_SYNTHESIS_REQUESTED_MORE_TOOLS`, no existing safety limit was raised or removed, and the legacy (non-native) `DefaultToolCallingRuntime` loop keeps its own separate, string-based return-site labels (`FINISHED`, `WAITING_APPROVAL`, `NO_TOOL`, ...) rather than being retrofitted with `ToolLoopTerminationInfo` - it is not the production path (`jarvis.tools.runtime: native`).

### Store Audit Dataset

Multi-step extraction tasks (the motivating case: reading store lists off attached photos to build a monthly audit schedule) must never rely on the model re-deriving *how many* records exist from memory or from its own "thinking" text at every tool-loop turn. `StoreAuditDatasetService`/`StoreDatasetTool` (native tool `storeDataset`, `jarvis-tools/.../dataset/`) hold that count as actual application state, and - the central design principle after a production run reached `location.GEOCODE_DATASET` with `VERIFY_DATASET` skipped entirely and still ended in a "geocoding summary" presented as a finished schedule - **every stage transition below is enforced in code with a rejecting `ToolResult`, never merely requested in the system prompt.** A model can skip a step, retry too early, mistype a record id, or declare only one of several real attachments; Core is designed to catch each of those and route the model back to the correct next step rather than silently drifting or completing early.

**State machine** (`DatasetStage`), each arrow a Core-enforced transition, not a suggestion - every operation also declares its own required starting stage(s), rejected immediately (`STORE_AUDIT_INVALID_STAGE`) if the dataset isn't there yet, so the pipeline order is never left to the model remembering a system-prompt instruction:

```text
BUILDING  --FINALIZE_DATASET-->  EXTRACTED  --VERIFY_DATASET (full N/N)-->  LOCKED
   --SET_PREFERENCES-->  LOCKED (preferences set)
   --[READ REQUIRED WORKFLOW DOCUMENT]-->  LOCKED (doc loaded)
   --GEOCODE_DATASET-->  GEOLOCATED  --SUBMIT_SCHEDULE (date-validated)-->  SCHEDULED
```

`SET_PREFERENCES` does not itself change `DatasetStage` - it sets `StoreAuditDataset#preferences()`, a separate, independently-tracked fact `SUBMIT_SCHEDULE` and the completion gate both key off. `REQUEST_USER_INPUT` similarly sets `pendingUserInput()` without changing stage - see [Scheduling Preferences & Date-Aware Scheduling](#scheduling-preferences--date-aware-scheduling) below.

| Operation | Required stage(s) | Rejected with |
|---|---|---|
| `APPEND_RECORDS` / `FINALIZE_DATASET` | `BUILDING` | `STORE_DATASET_...` (see below) |
| `VERIFY_DATASET` | `EXTRACTED` | - |
| `SET_PREFERENCES` / `REQUEST_USER_INPUT` | any except `BUILDING` | `STORE_DATASET_NOT_BUILDING` |
| `GEOCODE_DATASET` | `LOCKED`/`GEOLOCATED` **and** required document read | `STORE_DATASET_NOT_VERIFIED` / `STORE_AUDIT_WORKFLOW_DOCUMENT_NOT_LOADED` |
| `SUBMIT_SCHEDULE` | `GEOLOCATED`/`SCHEDULED`, preferences set, every day date-valid | `STORE_AUDIT_INVALID_STAGE` / `STORE_AUDIT_PREFERENCES_NOT_SET` / date-related codes (see below) |

`StoreAuditWorkflowCompletionValidator.nextRequiredAction(stage, documentLoaded, preferencesSet)` is the single source of truth for what comes next at every stage (see [Native Tool Calling & Tool Loop](#native-tool-calling--tool-loop)) - every guard rejection's `data.requiredNextAction` and the compact workflow status block both read from this one method, so there is only ever one implementation of this ordering to keep in sync. At `LOCKED`, the next action is `SET_PREFERENCES` until preferences are set, then `READ_REQUIRED_WORKFLOW_DOCUMENT`/`GEOCODE_DATASET` exactly as before.

- **`CREATE_DATASET`** (one-shot) / **`START_DATASET` + `APPEND_RECORDS` + `FINALIZE_DATASET`** (incremental, for large extractions) lock the extracted record list, assigning stable ids (`store-001`, ...).
  - **Provenance is bound to Core's real registered attachment set, not the model's declaration.** `ToolCallingStage` registers the actual current-message attachment ids (and the owning conversation id) with `StoreAuditDatasetService#registerAttachments` before the tool loop starts; `buildDataset`/`appendRecords` use *that* real set - not merely whatever subset the model's own `sourceAttachmentIds` argument happened to mention - as both the provenance-validation set and what gets stored on the dataset. This is what fixed the exact production bug: `START_DATASET` declaring only the first of two real attachments no longer causes the second attachment's `APPEND_RECORDS` batch to be rejected wholesale on provenance grounds. The model's own declared list is still checked as a sanity guard (an id it invents that isn't real is rejected), it just never *narrows* the legal set below the real one. A record without valid provenance is rejected individually, not silently accepted, and duplicate submissions for the same source row are deduplicated rather than growing the dataset.
  - **Core-owned index-to-id resolution (`sourceAttachmentIndex`)** - see [Current-Message Attachments vs Knowledge Workspace](#current-message-attachments-vs-knowledge-workspace) for the full mechanism. `CandidateRecord`'s internal shape still keeps a real `sourceAttachmentId` field for every record (traceability/provenance never regresses); only the *model's* responsibility for supplying that id changed, not the internal data model.
  - **`expectedRecordCount`** is a required argument on `CREATE_DATASET`/`START_DATASET`: the total number of source records the model counted before submitting any batch. `FINALIZE_DATASET` (and `CREATE_DATASET`'s own implicit finalize) rejects the transition to `EXTRACTED` as `STORE_DATASET_INCOMPLETE_EXTRACTION` when the accepted count doesn't match exactly, logged as `[STORE_AUDIT][INVARIANT_VIOLATION] expectedRecords=23 actualRecords=14 missing=9` - this is the direct fix for a 23-record extraction silently locking in at 14 because a later batch's provenance was rejected. As a second, fully automatic safety net that needs no model declaration at all, the same transition also rejects (`STORE_DATASET_ATTACHMENT_NOT_REPRESENTED`) if any real attachment the dataset is scoped to contributed *zero* records - catching a whole missing attachment even when the model never declared an expected count.
  - A submission that would result in zero real records for a one-shot `CREATE_DATASET` is rejected outright as `EMPTY_DATASET`; `START_DATASET` may legally land at zero (there is a later stage to add records at) but `FINALIZE_DATASET` still rejects finalizing an empty dataset.
  - A **second** `CREATE_DATASET`/`START_DATASET` for the exact same conversation and source attachment set is rejected as `STORE_DATASET_DUPLICATE_SOURCE`, returning the existing dataset's id instead - corrections go through `VERIFY_DATASET` against the existing id. A different attachment set in the same conversation (genuinely new photos) still creates its own dataset.
- **`VERIFY_DATASET`** requires **full coverage**: every canonical record referenced exactly once - no record missing, none duplicated, none hallucinated - mirroring the exact invariant `SUBMIT_SCHEDULE` already enforced on a proposed schedule. **The model references each record by Core-owned `recordIndex` (its 1-based position in the dataset, e.g. `1`..`23`), not by rewriting the internal id string.** This is the direct fix for a production failure where the model sent `store-01`..`store-23` (wrong zero-padding) instead of the real `store-001`..`store-023`, and Core correctly rejected the entire pass as `missing=23 unknown=23`. `StoreDatasetTool` resolves every `recordIndex` against the dataset's stable insertion-order record list *before* calling `StoreAuditDatasetService` - an out-of-range index (e.g. `24` on a 23-record dataset) rejects the whole call immediately as `STORE_RECORD_INDEX_OUT_OF_RANGE` (`data.recordIndex`/`data.recordCount`), never guessed or clamped to the nearest valid one; a duplicate index within the same call is rejected the same way the service already rejects a duplicate id. The legacy `recordId` argument still works standalone and, if supplied *together* with `recordIndex` and the two disagree, the call is rejected as `STORE_RECORD_REFERENCE_MISMATCH` rather than silently picking one. Internally the record still keeps its real `store-NNN` id (`CandidateRecord`/`StoreRecord`, unchanged) - only the model's responsibility for referencing it changed. A partial pass (e.g. 1 of 23 records) is rejected outright as `STORE_DATASET_INVARIANT_VIOLATION` with the precise `missingRecordIds`/`duplicateRecordIds`/`unknownRecordIds` lists, and the dataset stays `EXTRACTED` - it is never silently treated as "verified enough". Only a fully-covering pass advances the dataset to `LOCKED`.
- **`GEOCODE_DATASET`** (`location` tool) is rejected as `STORE_DATASET_NOT_VERIFIED` on any dataset not yet `LOCKED`/`GEOLOCATED` (i.e. `BUILDING`, `EXTRACTED`, or `SCHEDULED`) - this is the direct fix for the production run that jumped straight from extraction to geolocation, skipping verification. A `LOCKED` dataset is *additionally* blocked at the `NativeToolLoopService` layer until the required workflow document has been read this loop (see [Native Tool Calling & Tool Loop](#native-tool-calling--tool-loop)) - `LOCKED` alone is not enough to start geocoding. See below for its canonical, Core-sourced argument shape.
- **`SUBMIT_SCHEDULE`** requires the dataset to already be `GEOLOCATED` (or `SCHEDULED`, for a resubmission) - rejected immediately as `STORE_AUDIT_INVALID_STAGE` otherwise, so a schedule can never be accepted against records that were never geocoded even if the record-coverage invariant below happens to be satisfiable. **The model groups records into days by Core-owned `storeIndexes` (1-based positions), not by rewriting `storeIds` strings** - the same bug class as `VERIFY_DATASET` above showed up here too in production (`store-01`..`store-23` instead of `store-001`..`store-023`, rejected as `missing=23 unknown=23`). `StoreDatasetTool` resolves every day's `storeIndexes` array to canonical `storeIds` before the existing invariant validator runs, so that validator's own logic (below) is unchanged; an out-of-range index rejects the whole call as `STORE_RECORD_INDEX_OUT_OF_RANGE`. (Unlike `VERIFY_DATASET`, mismatch detection between `storeIndexes` and a legacy `storeIds` on the same day is not implemented - positional pairing between two arrays of possibly different lengths would be ambiguous, so `storeIndexes` simply wins outright when both are supplied; this is a deliberate scope decision, not an oversight.) Canonical record order is stable insertion order (never re-sorted) across `GET_DATASET`/`VERIFY_DATASET`/`SUBMIT_SCHEDULE`, so a given `recordIndex`/entry in `storeIndexes` always means the same record throughout one dataset's lifetime. Once resolved to real ids, the existing invariant validates a proposed day-by-day grouping against the locked dataset before it is ever presented as a final schedule: every record id must appear in exactly one day, exactly once - any missing, duplicated, or unknown/hallucinated id rejects the whole submission with the exact offending ids listed, logged as `[STORE_AUDIT_VALIDATION] datasetStores=... scheduledUniqueStores=... duplicates=... missing=... unknown=... valid=...`. Only an accepted submission sets `stage=SCHEDULED`, the workflow's normal successful terminal state (see the completion gate above).
- **`GET_DATASET`** returns the current record list (each entry including its `recordIndex`), verification/geolocation status, any set `preferences`/`pendingUserInput`, and any accepted schedule - the reference point every later tool call re-checks against instead of trusting memory of an earlier turn. A repeated call against an unchanged dataset is short-circuited by the no-progress guard described above rather than re-returning the full payload.

#### Scheduling Preferences & Date-Aware Scheduling

The user's actual decision about which days to use and how to spread audits across the month is a typed, persisted fact (`SchedulingPreferences`: `year`, `month`, `preferredDaysOfWeek`/`fallbackDaysOfWeek`, `strategy` (`BEGINNING`/`ENDING`/`EVEN`), an optional explicit date range, and whether Saturday was explicitly agreed to) - never left only in the model's free-text reasoning between asking the question and submitting the final schedule.

- **`SET_PREFERENCES`** records this decision on the dataset. `year` defaults to the current year (evaluated in `Europe/Warsaw`, via the same injectable `Clock` already used for TTL) when omitted or `0` - never guessed or hardcoded by the model. Rejected as `STORE_AUDIT_PREFERENCES_MONTH_IN_PAST` if the resulting year/month has already fully passed, `STORE_AUDIT_PREFERENCES_NO_DAYS` if neither a preferred nor a fallback day of week was given.
- **`REQUEST_USER_INPUT`** (`kind=AWAITING_PREFERENCES`/`AWAITING_DECISION`) marks the dataset as legitimately paused for a genuine question - see the completion-gate note below. Cleared automatically the moment any real forward-progress mutation happens (verification, geolocation, `SET_PREFERENCES`, schedule submission), so a stale pause can never survive genuine progress.
- **`SUBMIT_SCHEDULE`** now requires each day to carry a real `date` (ISO `YYYY-MM-DD`, model-supplied but Core-validated against a real calendar - `ScheduleDay#dayOfWeek()` is always *derived* from `date`, never a separately-suppliable field, so the two can never disagree) plus `routeDistanceMeters`/`routeDurationSeconds` (the day's full closed route - see below) and `auditDurationSeconds`; `ScheduleDay#totalWorkSeconds()` is `routeDurationSeconds + auditDurationSeconds`, always derived, never a separate input. Before the existing record-coverage invariant runs, `StoreAuditDatasetService` rejects the whole submission (nothing applied) if: preferences were never set (`STORE_AUDIT_PREFERENCES_NOT_SET`); any day has no date; any date falls before "today" (Warsaw-local, from the same `Clock`); any date falls outside the agreed window (the explicit range if the user gave one, otherwise the preferences' year/month); any date's actual day-of-week isn't one of `preferredDaysOfWeek ∪ fallbackDaysOfWeek ∪ {Saturday, only if explicitly allowed}`; any day is empty; or any distance/duration value is negative.
- **Completion-gate pause recognition**: a `LOCKED` dataset with `preferences()` still `null` is treated as an *expected, legitimate pause* - the canonical point in the pipeline to ask about scheduling preferences - so the tool loop lets the turn end with a genuine question instead of forcing further tool calls, with no extra `REQUEST_USER_INPUT` call needed for this common case. A dataset with `pendingUserInput()==AWAITING_DECISION` (set explicitly, for a rarer borderline planning tradeoff) is likewise never blocked. Every other non-terminal stop is still treated as genuinely incomplete and drives the existing bounded recovery mechanism, unchanged.

The dataset is conversation-scoped, not just request-scoped (`StoreAuditDataset#conversationId`, 2-hour TTL with periodic sweep): `StoreAuditDatasetService#findLatestForConversation` lets a *later* chat turn ("polacz dzien 3 i 4") continue against the same canonical records without the user resending the original attachments - `NativeToolLoopService` looks this up at the start of every tool loop and, when found, tells the model the dataset id/stage/record count directly in its system prompt instead of leaving continuity to conversation-history text. Nothing here is written to the permanent Knowledge Workspace; it is working state for the current task, not long-term user knowledge.

**Enforced, not just prompted**: a system-prompt nudge to use `storeDataset` before geocoding a large extracted list was tried first and was still ignored in production (the model geocoded a handful of addresses one at a time via plain `location.GEOCODE`, ran out of tool-loop turns, and presented a "schedule" silently covering only 2 of 23 extracted stores). `NativeToolLoopService` now tracks the cumulative address count passed to raw `location.GEOCODE` calls within one loop; once that exceeds a small threshold (4) without a `storeDataset` behind it (from an earlier turn, or created earlier in this same loop), further raw `GEOCODE` calls are blocked with an instructive error - the model must call `storeDataset.CREATE_DATASET` and use `location.GEOCODE_DATASET` instead, which is exactly the path the count-invariant checks above can actually see. Small legitimate batches (a handful of route stops) stay well under the threshold and are never affected. **A failed `CREATE_DATASET`/`START_DATASET` attempt tightens this further**: once such a call has failed and no dataset yet exists for the loop, raw `GEOCODE` is blocked outright (`errorCode=RAW_GEOCODE_AFTER_DATASET_FAILURE_BLOCKED`) regardless of the address-count threshold, until the model retries the dataset call successfully - raw geocoding must never become a silent workaround for a `storeDataset` call the model got wrong. The final-answer synthesis prompt also now requires the model to name any ambiguous/unresolved geocode result explicitly (never silently pick a candidate) and to state the dataset's exact record count when one was used.

**Large extractions can be built incrementally.** A single `CREATE_DATASET` call asking the model to populate one large `records` array (observed failing around 20+ records) can fail outright - not a parsing error, the model emits a genuinely empty `records` array despite its own reasoning stating it will populate it, a known weak spot for native tool-calling with big structured array arguments; the [nested native tool JSON schema fix](#native-tool-calling--tool-loop) above closes the main cause of this, but the incremental path stays useful for genuinely large batches regardless. `storeDataset.START_DATASET` begins a dataset in `DatasetStage.BUILDING` with a first small batch (5-8 records) - **unlike `CREATE_DATASET`, `START_DATASET` legally succeeds with zero records** (there is a later stage, `APPEND_RECORDS`, to add them; only `FINALIZE_DATASET` rejects a dataset that is still empty, or short of `expectedRecordCount`, once the model is done submitting batches). `storeDataset.APPEND_RECORDS` adds further batches (deduplicated across every batch, not just within one), and `storeDataset.FINALIZE_DATASET` locks the record count and advances it to `EXTRACTED` - from there it behaves exactly like a dataset created in one `CREATE_DATASET` call. `VERIFY_DATASET`/`GEOCODE_DATASET`/`SUBMIT_SCHEDULE` all reject a still-`BUILDING` dataset with a clear "call FINALIZE_DATASET first" message - a half-built dataset can never be geocoded or scheduled by accident. The duplicate-source invariant above applies identically to `START_DATASET`; `FINALIZE_DATASET` on an already-finalized dataset is a safe no-op (bounded retries in the agent loop above can hit this without it being a real error).

**`GEOCODE_DATASET` is canonical - the model can never resend a record's id or address.** The operation's argument shape used to be `{datasetId, records: [{recordId, fullAddress}]}`, letting the model restate a record's id and address on every geocoding call - in production the model once resent `013` instead of the real `store-013`, and the record was silently skipped (reported only as an ignored unknown id). The shape is now `{datasetId, recordIds?}`: `location.GEOCODE_DATASET` reads each target record's canonical `id()`/`fullAddress()` directly from the locked dataset itself and writes the geocoding result back to that exact record - the model supplies at most which existing ids to (re)geocode, never an id or address value. Omitting `recordIds` geocodes every record in the dataset; passing it geocodes only that subset (e.g. to retry records that came back ambiguous/unresolved) - an id that isn't a real canonical record is reported under `unknownRequestedRecordIds` and simply never geocoded, never used to invent a record or accept an alternate address.

**Tool name lookup is case-insensitive**: native function names sent to the model are always lowercased (`NativeToolSchemaMapper` builds e.g. `storedataset__create_dataset` from the tool's own `getName()`), and the model echoes that lowercased name back verbatim on every call. `StoreDatasetTool#getName()` returns `"storeDataset"` (mixed case) for readability in logs/docs, which meant every real `storeDataset.CREATE_DATASET` call the model made was rejected with `Tool not registered: storedataset` - the registry (`DefaultToolManager`) was keyed by the tool's exact-case name, so the lowercase name the model actually sent never matched. In production this made the raw-geocode guard above actively harmful: the model would try `storeDataset.CREATE_DATASET` (as instructed), get an opaque "not registered" error, retry it a couple of times, give up, fall back to raw `location.GEOCODE`, and then run straight into the guard - burning most of the tool-call budget on calls that could never have succeeded. `DefaultToolManager` now normalizes both registration and lookup keys to lowercase, so any tool's `getName()` casing is safe regardless of how it is echoed back by the model.

### Coding Workspace Tools

The Coding Agent is exposed to the ordinary chat/model pipeline as the native `coding` tool family. Windows UI assigns an active Coding Workspace to the current conversation and sends its `workspaceId`, display name, and host metadata with every chat stream request. Core injects that workspace into tool execution; the model receives the active workspace metadata but does not choose or change the workspace itself.

The model-facing functions are `coding__workspace_inspect`, `coding__file_list`, `coding__file_search`, `coding__file_read`, `coding__file_write`, `coding__file_patch`, `coding__directory_create`, `coding__file_move`, `coding__file_delete`, `coding__git_status`, `coding__git_diff`, `coding__build_detect`, `coding__command_start`, `coding__command_poll`, `coding__command_cancel`, `coding__build_run`, and `coding__test_run`. They all delegate to `CodingService`; there is no separate filesystem/process implementation in the chat runtime.

For `host=WINDOWS`, `CodingService` delegates through the existing Windows Bridge using `CODING_EXECUTOR_REQUEST` with `server=coding`, so Core never interprets `D:\...` paths with Ubuntu `java.nio.file` and never falls back to local filesystem/process execution. For `host=SERVER`, the existing server-local implementation remains available. Knowledge tools remain scoped to the persisted Knowledge Workspace and must not be used as a fallback for project files in an active Coding Workspace.

Safe diagnostic logs are emitted without token or file-content dumps: `[NATIVE_TOOL_SCHEMA]` records the count and names of tools passed to the provider plus the active workspace id, `[NATIVE_TOOL_CALL]` records the selected native function, and `[CODING_TOOL]` records the delegated coding operation and workspace id.

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
- **`OPTIMIZE_ROUTE`** - given a `start` point and a list of `stops`, proposes a visiting order minimizing total distance or time (`optimize: "distance"|"time"`). This is the operation behind "group these addresses and propose a visit order", "best order to visit these stops", "plan a route through these places" style requests, regardless of whether the addresses came from typed text, an image, a file, or Knowledge. With `returnToStart: true`, the visiting order search itself targets the **closed-loop** cost (start → every stop → back to start, e.g. a Store Audit day trip that must end back at its fixed base) rather than the open path, and the response adds `totalDistanceMeters`/`totalDurationSeconds` for the *whole* closed route (both units, independent of which one `optimize` targeted) plus a `closedRoute` boolean and a final return-leg entry in `legs`. If the specific return edge back to the start can't be resolved, the response falls back to open-route totals with `closedRoute: false` and a warning, rather than fabricating a return-leg distance/duration. This closed-loop search is an extension of the existing open-path optimizer (exact for small stop counts, nearest-neighbour+2-opt heuristic above that - see below) rather than an independent certified Hamiltonian-cycle solver; see [Known Limitations](#known-limitations).

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
- Route optimization uses a heuristic (nearest-neighbour + 2-opt) above `exact-optimization-max-stops` stops - not a guaranteed-optimal TSP solution. The `returnToStart` closed-loop mode reuses this same search (scored against the closed-loop cost instead of the open-path one) rather than an independent Hamiltonian-cycle solver - reasonable in practice, not a certified optimum.
- Store Audit's `BEGINNING`/`ENDING`/`EVEN` distribution `strategy` is recorded as data and is guidance for the model's own planning (per the canonical Knowledge Workspace document), not a hard code-level constraint - `SUBMIT_SCHEDULE` validates every date against the agreed month/explicit range and allowed days of week, but does not itself reject a `BEGINNING`-strategy date that happens to fall in the second half of the month. This mirrors the existing "guidelines, not hard limits" treatment of the daily Biedronka/short-audit counts.
- `SUBMIT_SCHEDULE` requires the dataset to be `GEOLOCATED` (every record has been through at least one geocoding attempt) but does not itself require every scheduled record's `geolocationStatus` to be `RESOLVED` - a record left `AMBIGUOUS`/`FAILED` after geocoding can still be scheduled if the model (per the canonical document's guidance) judges it appropriate; presenting an unresolved/failed geocode as if it were confirmed is a prompt-level responsibility, not a code-level gate.
- Candidate-based geocoding validation reduces but does not eliminate the risk of an incorrect match - it only evaluates address details actually present in both the query text and the provider's structured response; a query with no postal code, street, or region to disambiguate a common place name can still land on `AMBIGUOUS`/`NOT_CONFIDENTLY_RESOLVED` rather than a wrong-but-confident answer, which is the intended fail-safe behavior.
- Ollama thinking-token counts are estimated (characters/4), not exact tokenizer counts, everywhere in this codebase - reported fields are explicitly labeled as estimates where relevant.
- There is no cross-repository version compatibility matrix between this backend and the Windows client; they are versioned and released independently.
- The native tool loop's re-entry/completion-gate retries (`MAX_MALFORMED_CONTINUATION_ATTEMPTS`, `MAX_COMPLETION_GATE_ATTEMPTS`, plus the GoalContract verifier budget) are bounded heuristics, not a formal proof the model will eventually produce a genuinely complete answer - a persistently confused model still eventually gets an honest incomplete-workflow answer rather than hanging forever. `WorkflowCompletionValidator` currently has one concrete implementation (Store Audit); other stateful workflows rely on the generic GoalContract lifecycle until they add their own validator.
- `AttachmentRetrievalIntentDetector` (see [Current-Message Attachments vs Knowledge Workspace](#current-message-attachments-vs-knowledge-workspace)) matches on action-word + attachment-noun combinations, not full NLU - an unusually phrased redundant-retrieval request could in theory slip through undetected (falls back to normal tool routing/honest not-found reporting), and a legitimate goal that happens to combine both word categories without meaning "fetch the image" could in theory trigger one unnecessary internal retry; the one-retry budget bounds the cost of either case.
- The live-streaming structured-envelope detector's prose-preamble tolerance (see [Native Tool Calling & Tool Loop](#native-tool-calling--tool-loop)) is a *bounded* probe window, not unlimited lookahead - a `TOOL_REQUEST` envelope preceded by prose longer than that window could still stream as raw text before the [Final Protocol Guard](#native-tool-calling--tool-loop) can catch it in the returned/stored value (which happens too late to un-stream already-published tokens). Widening the window trades off added latency before any plain-text answer starts streaming, so it is a deliberately modest bound rather than an unbounded wait.
- [Typed Tool-Loop Termination Reason](#typed-tool-loop-termination-reason) explains *why* the loop stopped honestly, but does not itself resume it - `FINAL_SYNTHESIS_REQUESTED_MORE_TOOLS` still ends the request; a follow-up message from the user re-enters a fresh loop with the earlier tool results no longer directly available (only whatever survived into conversation history). `MCP_FAILURE` classification only fires when every single executed call this loop was an MCP call that failed - a mixed MCP/native-tool all-failure run still reports `MAX_TURNS_REACHED`/`TIMEOUT` with the failures visible via `lastErrorCode`/`lastErrorMessage` rather than a dedicated reason of its own. `remainingGoalCriteria` reflects the goal contract's originally defined criteria (never individually marked satisfied - see `GoalContract`/`CompletionCriterion` in code), not a live per-criterion completion state, since no such granular tracking exists in the goal-verification model today.
- [Conversation-Scoped Image Memory](#conversation-scoped-image-memory)'s reference resolver is deterministic pattern matching (ordinal words, `image-N` labels, file names, "which message" qualifiers), not full NLU - an unusually phrased reference could fail to resolve to one specific image and instead fall back to the general/vague-reference behavior (or to nothing, if `auto-attach-mode: REFERENCED_ONLY`), never to a wrong image. The Windows client currently has no conversation-history-reload feature at all (a fresh `conversationId` is minted once per app launch); the per-attachment status line is patched onto messages already in the current running session only and is refreshed by the next `CONVERSATION_IMAGES` event rather than a continuously-ticking countdown. If `jarvis.conversation.images.retention` is configured longer than `jarvis.workspace.ttl`, the physical file still disappears at the shorter workspace TTL - Core logs this inconsistency once at startup but does not raise either value automatically.
