# Companion Phase 2 — Core implementation and rollout

Date: 2026-11-25. Repository: `SERVER`, branch `feature/companion-core`, baseline `a0cc3c7`. Implementation is included in this Phase 2 release. Windows sources and the Ubuntu installation were not changed.

## Final deployment target and measured basis

The owner's latest measurements supersede the earlier 16K target:

- Four Ollama slots, one interactive reservation, at most three background inference leases.
- **Stable physical `num_ctx=24576` for the primary model**, not a different context per operation.
- Operator-recommended Ollama settings: `OLLAMA_NUM_PARALLEL=4`, `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`.
- Reported aggregate throughput 146.15 tok/s, 100% GPU, total GPU including voice 14711/16380 MiB.
- Idle TTFT/first content 159/342 ms; during concurrent long-prompt processing 1116/3367 ms; during background generation 279–288/762–811 ms.

These are owner-supplied hardware measurements, not results from the local test harness. The full table and earlier 8K/16K/32K results are in `OLLAMA_CONCURRENCY_BENCHMARKS.md`. A reserved admission slot does **not** reserve GPU compute or bandwidth. Gateway priority cannot preempt a request already running in Ollama. Mixing physical contexts previously caused reloads and 89.50 s TTFT; this implementation does not do that for logical profiles.

## Admission, priority and cancellation

`OllamaRequestCoordinator` implements the sole process-local `ModelGateway`. Capacity is dynamically assigned to requests, not permanently to agent identities. Source application.yml and Spring defaults select four slots and one interactive reservation. Background occupancy is capped at three. Queue order uses profile priority, FIFO tie-breaking and aging (one priority point per two seconds) to protect old background work. Queue size, context-token ceiling and JVM heap guard remain bounded/configurable. Heap is not treated as VRAM.

`jarvis.model-gateway.background-submit-spacing-millis` defaults to **250 ms** in Spring; 0 disables it. It spaces background admissions to reduce bursts of prompt submissions. Waiting for spacing consumes no inference slot; foreground requests do not wait for this timer. Existing background generation can continue concurrently. This is a tunable mitigation, not a measured optimal value, not a prefill detector and not a latency guarantee. Compare 0/100/250/500 ms under the same long-prompt arrival pattern before selecting a production value.

A blocked nested admission on a thread already holding a lease fails instead of deadlocking. Provider inference leases cover transport through stream completion/close, not tool execution. Queue waits honor interruption and the resolved execution deadline. `InferenceDeadline` interrupts the header wait, watches cancellation and closes stalled response bodies; only the request owner releases admission. An incomplete stream without `done:true` now fails for plain generation, image chat and native tool chat. A content-bearing final marker is consumed correctly.

Additional production inference paths are covered: moderation, active-model vision capability probes, startup warmup, describeImage, Core embeddings and the dedicated vault Ollama embedding backend. Metadata calls (`tags`, `show`) do not consume generation capacity. Qwen continuation retains its existing parent lease and deadline instead of acquiring another lease. Legacy constructors remain for isolated test/harness use; production Spring wiring supplies the gateway. The standalone Coding Service executor is explicitly marked background and uses the coding profile; it cannot accidentally consume interactive admission priority.

The gate is process-local. Other Core processes and direct external Ollama clients are outside its counters/control. No automatic Ollama restart, unload, model substitution or GPU memory reconfiguration was added.

## Model-aware profiles and context

`InferenceProfileRegistry` resolves independently configurable model, input/output limits, reasoning, priority, timeout, classification, selection strategy and resource-class label. A background parent cannot promote itself through an INTERACTIVE profile. Settings are scoped and restored across synchronous nested work; asynchronous task/title/memory/coding workers explicitly establish their own context.

| Profile key | Logical input cap | Output cap | Default reasoning | Classification |
|---|---:|---:|---|---|
| main-conversation | remaining physical context | 2048 | MEDIUM | interactive |
| conversation-title | 2048 | 128 | LOW | background |
| memory-extraction / memory-decision | 4096 | 768 | LOW | background |
| research | 8192 | 2048 | MEDIUM | background |
| planning | 8192 | 1024 | MEDIUM | background |
| coding | 16384 | 4096 | HIGH | background |
| tool-selection | 8192 | 768 | LOW | inherits background parent |
| tool-result-processing | remaining physical context | 2048 | MEDIUM | inherits background parent |
| dreaming | 8192 | 768 | MEDIUM | background, currently no LLM call |
| acknowledgment / progress | 1024 | 96 | MEDIUM | definitions available; normal notifications are deterministic |
| warmup | remaining physical context | 16 | MEDIUM | background |

Keys live under `jarvis.inference.profiles.<key>`, with `input-tokens`, `output-tokens`, `reasoning`, `priority`, `timeout-seconds`, `classification` (INHERIT/BACKGROUND/INTERACTIVE), `selection` (RECENT/EXTRACTIVE/STRICT), `model` and `resource-class`. Resource class is a label; no unmeasured per-class GPU cost is invented. Default model is the request's resolved model, so all operations can share GPT-OSS 20B. The combined main-model answer/tool decision remains a single operation; defining a tool-selection profile does not introduce another mandatory inference call.

`ModelMetadataResolver` reads `/api/show` architecture plus exactly `<architecture>.context_length`, and `/api/tags` digest. It does not infer limits from the tag's spelling or an unrelated metadata key. Per-model refresh is single-flight and cached for five minutes; switching the active model invalidates metadata and rejects a switch whose limits cannot be verified. In-flight calls keep an immutable resolved plan. Out-of-band changes to a tag require metadata refresh/invalidation or a controlled restart; pin/manage model versions during rollout.

Production physical context is `min(configured physical context, verified model maximum)`. Unknown/malformed model limits or missing digest fail closed. For GPT-OSS's reported 131072 maximum, old `jarvis.ai.context-window=262144` cannot authorize a larger runner or input budget. Legacy `reserved-output-tokens=30000` is not mistaken for an output limit. Actual generation sends explicit `num_predict` from the profile; actual physical context comes from `jarvis.inference.physical-context-window`, not a logical profile or legacy global window.

Input budgeting subtracts output plus a max(256, 5% physical) margin, then includes system/message framing, tool schemas/calls/results and image allowance. At 24576/2048, main input is at most 21300 estimated tokens before per-message/tool accounting. The production estimator uses conservative UTF-8 byte accounting; it is an estimate, not an exact tokenizer guarantee.

Native contexts select a bounded relevant tool set (`max-offered-tools:16`), retain system instructions and the latest dependent tool-call/result pairs, select older turns and use clearly marked historical excerpts. Explicit reference-only conversation blocks can be reduced separately from system instructions. Large tool results are archived in the existing ToolOutputStore and replaced with valid JSON containing an outputId and an explicitly partial preview; full data can be paged through `output__read`. Existing sourced investigation checkpoints/summarization remain available and now use model-aware input budgets. Mandatory context that still cannot fit is rejected instead of silently corrupting schemas or dropping required tool pairs. Plain prompts use bounded head/tail compaction with an omission marker; this does not provide unlimited lossless history.

Vision descriptions use their own profile context so a research profile's model override cannot accidentally replace an explicitly selected vision model. Optional side models/CPU placement can still affect Ollama residency; stable primary num_ctx does not promise that every possible multi-model workload avoids eviction.

## Real asynchronous delegation and dialogue

New `CompanionTaskSubmission` boundary and `CompanionDelegationTool` expose `companion__submit/get/cancel/instruct` without introducing a tools→api dependency. SUBMIT persists a real task and submission key, schedules it and returns a receipt without waiting for results. Repeated identical submission calls in one request resolve to the same task; different goals/roles remain distinct. A successful tool receipt omits unavailable optional values rather than passing null data into ToolResult.

Each new task has its own taskId, execution conversation `companion-<taskId>`, owner, state, deadline, cancellation and result/error. `conversationId` on Task links the originating conversation for tool-created tasks; `executionConversationId` identifies the isolated execution. Older history may have null links. Owner authorization is taken from authenticated context, never model arguments. Nested background SUBMIT is rejected; synchronous helpers cannot recursively spawn new helpers/async agents. `agent__run` remains synchronous for immediately dependent results.

After a successful SUBMIT, the conversation's tool loop returns a **deterministic receipt acknowledgment** with terminationReason `DELEGATED`, `completed=false`, `goalSatisfied=false`. It does not call the model again to narrate acceptance, and does not wait for the child. Task completion is a separate event. This makes “accepted” distinguishable from “completed” throughout diagnostics.

Additional task instructions can be sent through `companion__instruct` or `POST /api/v1/companion/tasks/{id}/instructions` with `{message}`. The bounded mailbox accepts 16 messages of at most 4000 characters and consumes them between rounds. If a plain-response task receives an instruction while generating, a subsequent turn handles it before finalization. The completion race closes instruction admission explicitly; a late instruction returns conflict instead of being accepted and lost. Cancellation interrupts the actual worker; terminal cancellation waits for execution to leave. Instructions are cooperative, not GPU preemption or rollback of tool side effects.

The existing task journal, persistent history, plans/evidence and restart recovery remain. Durable history is configurable (`jarvis.companion.history.enabled`); when disabled, capabilities correctly report non-durable history. Restarted active tasks fail rather than automatically retrying external side effects. Submission keys are stored with task creation and removed on task-history deletion. SQLite bulk writes provide the durable transaction boundary.

`TaskAnnouncements` is now event-driven templates only, even when enabled: accepted, started, completed and failed messages require the corresponding actual state and use no router/provider call. Title generation has a bounded 64-item queue, owner/conversation deduplication, lifecycle cleanup and the explicit low-reasoning title profile. Autonomous Memory and Dreaming remain configurable and disabled by default; current Dreaming consolidation itself is deterministic.

The main decision and native tool prompts identify J.A.R.V.I.S., distinguish synchronous helpers from asynchronous tasks and require execution evidence. Receipt-based acknowledgment bypasses free-form claims. Conservative narration guards suppress recognized unbacked agent-start claims; requested-delegation answers are held for validation. These guards are not a general semantic truth oracle. UI activity and task existence must always come from backend receipts/events, never model narration.

## Activity contract 1.5.0 and coordination with Claude

Versioned schemas are in `../schemas/1.5.0`; previous schema versions are retained. New optional capabilities are `inferenceTelemetry`, `asyncDelegation`, `taskInstructions`.

`/state` and `system.snapshot` add `metrics`. Subscribed companion sockets emit transient `system.metrics` about once a second with sequence 0; these frames do not advance journal replay cursors. Clients must process them like other control/snapshot frames and not deduplicate them by the journal sequence. Ordinary task/plan/instruction events continue using the journal sequence and serverInstanceId.

Metrics distinguish owner-scoped active Companion/Coding work, queued Companion tasks, active synchronous helpers, active tool executions, admitted inference requests, queued inference requests, and global gateway occupancy/configured capacity. `activeCompanionTasks` and `activeCodingTasks` are separate measured worker counts; `activeTasks` sums them and `activeAgents` also includes tracked synchronous helpers. Standalone Coding retains its existing CODING_TASK_* lifecycle events and task store, uses background gateway admission, and is not duplicated into the Companion task list. Queued Companion work is reported by `queuedTasks`; standalone Coding lifecycle/WAITING_FOR_USER details continue to come from its existing events rather than a fabricated common queue count. `activeProcesses` keeps its previous meaning (all nonterminal Companion tasks), including queued/cancel-requested states. Do not use it as an inference count or merge all counters into a fabricated total.

Gateway details carry inferenceId, requestId, taskId when present, profile, model, physical context, input budget, real admission queue wait and elapsed admitted time. `ADMITTED` is an inference request lifetime, not proof that the GPU is currently decoding. CapacityScope=PROCESS identifies process-wide occupancy; work lists and counts are owner-filtered. Resource samples are read-only host `/proc/meminfo` and bounded nvidia-smi polling; fields are omitted when unavailable/stale. GPU allocation includes other processes such as voice; no static Whisper allocation or heap-derived VRAM value is inserted. Host RAM availability is not cgroup memory headroom.

`OLLAMA_METRICS` now also covers native tool-chat final timings. `phaseTimingSource=OLLAMA_FINAL_RESPONSE` and `phaseTimingsMeasured` distinguish actual reported prompt-evaluation/generation durations from unavailable data. Native missing durations are omitted. Existing completed-phase traces identify OLLAMA_PROMPT_EVAL and OLLAMA_GENERATION. Before the first streamed output, transport/runtime queue/load/prefill remain unresolved; no guessed live PREFILL phase or exact runtime queue time is exposed.

Results remain in `task.completed.payload.result` and history, with the source conversation link. Windows should route/render those results and meaningful status notifications, deduplicate journal events and avoid speaking both a receipt acknowledgment and an equivalent task announcement. Core does not automatically append task results to the source conversation's stored message stream. The owner confirmed final Windows/Core 1.5.0 compatibility for this controlled release; the local checks below are Core validation, not an independent Windows retest. See `PHASE2_CORE_COORDINATION.md` for the concise handoff.

## Verification

### Full regression

Java **21.0.9**, Maven **3.10.0**, full reactor `package`: **977 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. Evidence: `local validation log (not committed; summary in PHASE2_TEST_RESULTS.json)`. Coverage includes real overlapping provider HTTP (three background plus chat), fourth-background reservation, interactive admission during background spacing, profile/context enforcement, metadata cache/invalidation, actual stalled-HTTP cancellation/deadlines, incomplete stream failure, independent task lifecycle/owner isolation/cancellation/instructions, deterministic delegation receipts, template-only notifications and title settings. Existing full regression suites for tools, memory, history/recovery, plans/evidence, voice and Coding also passed.

### Production application context startup

Both the non-web bean-graph test and **ProductionWebContextTest** passed. The real servlet context has one production health/auth/Companion controller set, verifies four-slot/one-reservation admission, and excludes integration-controller classes. No production database or real Ollama was used.

### Packaged JAR startup

Final production executable JAR smoke **PASS** with isolated SQLite, a loopback fake Ollama and ephemeral HTTP ports: READY, bootstrap/authentication, 1.5.0 capabilities, metrics 4/1/250ms, ordinary chat, REST asynchronous task completion, actual main-conversation companion__submit receipt, isolated RESEARCH native tool execution, and stable physical **24576** in every inference payload. Fake inference call count: 9. These are functional tests, not GPU performance measurements. Evidence: `local isolated smoke verification.json (not committed)` and `startup.log`; the reproducible runner is `verify_packaged_core.py`. Archive inspection confirmed no `BOOT-INF/classes/com/jarvis/integration/` production classes.

Artifact: `javac-output-test/jarvis-core/jarvis-core-2.24.0-SNAPSHOT.jar`.

SHA-256: `aabd2a47272ee143446e536a52d5e108ffcff91202a1510a038af01ef72e447c`.

### Benchmark harness

**5 Python tests passed**: real local HTTP overlap with 1/2/3/4 clients, Core SSE usage/phase timing, deadline interruption, HTTP error and incomplete stream handling. The harness accepts four clients and derives Core runtime rates only when every reported inference has corresponding complete usage/timing metadata. Runtime queue time remains null when unavailable. No production inference benchmark or Ubuntu deployment was performed by Codex.

### Contract artifacts and source checks

Version 1.5.0 schema/example JSON parsed successfully and local references resolve; exported files in SERVER/docs/companion and SHARED match. Historical schemas are retained. `git diff --check` passed. Full machine-readable evidence: `PHASE2_TEST_RESULTS.json`. Windows/Core 1.5.0 compatibility was confirmed by the owner. Ubuntu deployment/hardware acceptance is separate from these local Core checks.

## Operator rollout (not executed)

1. Review and commit the working-tree changes/contract with Claude before a production build; verify that the Windows build accepts contract 1.5.0, sequence-0 metrics and terminationReason DELEGATED. Keep old clients that reject newer minor versions out of the rollout until upgraded.
2. Build from `SERVER` with Java 21: `mvn package`. Run `python -m unittest discover -s scripts/companion -p test_benchmark_inference.py -v`. Run `verify_packaged_core.py --java <java> --jar <packaged jar> --out <new isolated directory>` locally; it uses a loopback fake, not production Ollama.
3. Back up existing deployment configuration and databases; preserve paths, ownership and permissions. Do not replace production databases/config with smoke-test files. Enable/reuse `jarvis.companion.history.enabled=true` and a dedicated durable history path if persistent task history is required.
4. In an operator-controlled maintenance window, configure the Ollama service with the three recommended environment variables above and a stable 24576 context. Environment changes require the operator's service restart; record actual runner settings, q8_0 cache/flash-attention acceptance, placement and voice residency. Core never writes these settings.
5. Merge the Core configuration: `jarvis.model-gateway.concurrency=4`, `reserved-interactive=1`, `background-submit-spacing-millis=250` (or a separately measured value), `jarvis.inference.physical-context-window=24576`, logical profiles, and `jarvis.companion.task-concurrency=3`. Remove/align obsolete 262144/30000 overrides. Do not set a different physical num_ctx for titles, memory or agents. Keep Autonomous Memory/Dreaming disabled pending integration validation.
6. Start one Core instance for the admission pool. Check authenticated capabilities/state, model metadata verification, health/warmup, 4-slot capacity, physical 24K request options, queue release, cancellation, real task receipt/result and reconnect behavior.
7. Run operator-owned short/long/mixed benchmarks with voice enabled. Validate p50/p95 first output and first content separately during background prefill and generation. Compare spacing values, input budgets and aggregate throughput; do not choose only by aggregate tok/s. Check GPU/RAM/offload and runner logs. A latency regression can be mitigated by smaller logical inputs, lower background occupancy or deferring optional work; gateway priority cannot interrupt a running prefill.
8. Roll back configuration/artifact in a controlled stop/start if needed, preserving databases. Task execution interrupted by a restart is not automatically retried. Stable-context changes are maintenance operations, never per-agent adjustments.

## Remaining limits

No GPU preemption, distributed admission or automatic KV/cache/physical-context adaptation. Resource telemetry is observational; no speculative VRAM cost model or automatic capacity increase is applied. Model metadata can be stale until its TTL/invalidation boundary. Exact tokenizer/template/multimodal costs remain estimated, so mandatory oversized contexts fail clearly. ToolOutputStore is bounded process memory; archived previews are not a durable infinite evidence archive. Long-running task instructions cannot undo already executed effects. Windows/Core 1.5.0 compatibility was confirmed by the owner; the 250 ms spacing value and Ubuntu rollout still require operator-controlled production latency qualification.
