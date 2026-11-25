# Companion Phase 2 production delegation integration fix

## Confirmed findings

At baseline 89b722e, NativeToolLoopService returned DELEGATED after the first model round containing any successful companion SUBMIT. Three submissions in one model response worked, but three sequential model responses stopped after the first receipt. API_CONTRACT.md repeated that incomplete rule. Core now counts distinct backend task IDs against the explicit request cardinality and continues submitting the remaining goals before acknowledging. It never waits for their completion.

The reported incident's missing HTTP tool catalog is NOT established by model reasoning. An existing baseline packaged-JAR smoke recorded 133 registered/native-scope functions, including all four companion functions and agent__run. The existing budget selector already ranked Companion first. That preselection log did not prove the final HTTP payload in the reported production session. See PRODUCTION_DELEGATION_CATALOG.json for the complete baseline inventory and the distinction between observed and unknown evidence. Do not describe this incident as proven missing Spring beans.

The unsafe integration gaps were lack of a dedicated explicit-delegation route, a broad native catalog that permitted unrelated Calendar actions, editable/model-derived routing context, and premature completion after the first submission. The new route is based on the original current user message, bypasses the extra main-model capability decision, offers only Companion's four functions, and rejects unrelated calls even if a model invents them. It does not increase max-offered-tools. Requests outside this narrow route retain existing workflow/provider behavior.

The quoted legacy instruction originated in NativeToolLoopService (introduced in 46cafe8), and was already replaced by a receipt-aware recovery instruction in 89b722e. It is absent from the current source and tested packaged classes. Its appearance in the production trace therefore requires checking the actual deployed JAR/dependency hashes, process command line, historical prompt context and editable prompts; repository commit alone does not establish the bytes running. This fix removes remaining contradictory comments and strengthens ongoing-work validation: only a latest successful Companion receipt with QUEUED, RUNNING or CANCEL_REQUESTED supports continued background work. A completed task or synchronous agent report does not authorize an ongoing-work claim.

## Production path and capability policy

Authenticated /ws/jarvis -> JarvisWebSocketHandler -> ChatService/CognitivePipeline -> ModelExecutionStage -> TOOL_REQUEST -> ToolCallingStage -> NativeToolLoopService -> NativeToolSchemaMapper/ToolRegistry -> ContextBudgetService -> OllamaProvider -> DefaultToolManager -> CompanionDelegationTool -> CompanionTaskSubmissionAdapter -> CompanionTasks.

Core owns async capability instructions independently of editable identity/global/folder prompts. The regression supplies stale identity AND authenticated user settings saying that only Calendar/Gmail/Knowledge exist. Core still routes the explicit request and provides the real native schemas. Model-generated subagent context is removed by ToolCallingStage; real synchronous helpers enter their own Core-created native loop. Background TaskExecutionContext still suppresses recursive SUBMIT. Existing owner checks remain authoritative.

For explicit async delegation the actual HTTP catalog is exactly:

- companion__submit
- companion__get
- companion__cancel
- companion__instruct

agent__run remains a synchronous helper for immediately needed results; it is intentionally absent from the async submission route. Google Tasks/Calendar create external records, and taskplan manages the current task's plan. Neither starts a Companion agent. General connected-service and workflow routes retain their existing catalog and tests.

[OLLAMA_NATIVE_CATALOG] logs the actual post-selection function names, model, operation profile, think value and options immediately before transport/admission. NATIVE_TOOL_SCHEMA remains an earlier scope log. Use both with request IDs; do not infer capability absence from generated reasoning.

## Lifecycle and idempotency

An explicit request for three independent background agents must produce three distinct persistent taskIds in a single chat turn. Native calls may arrive together or over successive model rounds. A receipt for one task is insufficient. Core emits deterministic acknowledgment only after all requested receipts exist, with DELEGATED, completed=false and goalSatisfied=false. No additional model inference narrates this acknowledgment. The scheduler executes the isolated tasks concurrently; normal chat returns while tasks are still running. GET, INSTRUCT and CANCEL remain per-task and owner-bound. Completion events contain real task results.

The native submission key is scoped by authenticated owner, chat request ID, role and exact goal text. The regression repeats each of three different goals with the SAME chat request key: retries return the original IDs, while the three goals retain three IDs. This is an internal retry guarantee, not a new REST idempotency contract. Identical goal and role within the same request intentionally mean the same task. Creation of a group is not transactional: capacity/provider failures can leave accepted tasks active; never claim an uncreated remainder was accepted.

The narrow deterministic cardinality recognizer covers the tested English/Polish explicit requests, including “Run three independent background agents” and “Uruchom trzech niezależnych agentów w tle”. Ambiguous descriptions and other languages still require normal model interpretation; this is not a general natural-language parser. The controlled deployment acceptance request should name three distinct goals explicitly.

## Profiles and production configuration

No Ollama/GPU or gateway configuration changes are required. Keep the benchmarked tuple: OLLAMA_NUM_PARALLEL=4, OLLAMA_FLASH_ATTENTION=1, OLLAMA_KV_CACHE_TYPE=q8_0, physical num_ctx=24576; Core gateway concurrency=4, reserved-interactive=1, Companion task-concurrency=3, background-submit-spacing-millis=250. Keep Autonomous Memory, Dreaming, background-agent and background memory processing disabled. The complete mergeable production application.yml overlay is config/examples/application-companion-phase2.yml.

Explicit submission uses the existing tool-selection profile: logical input 8192, num_predict 768, think low, interactive priority 10. Physical context remains 24576 for every tested call. Ordinary chat retains its existing main profile; background work retains planning/research profiles and spacing. A per-call num_predict limit is not a cumulative chat-turn limit: several native rounds contribute to token totals. The incident's 5725 tokens/103 seconds cannot establish a violated per-call limit without the individual request payloads. This fix removes the extra capability-decision inference and the unrelated-tool exploration route. Production logs now expose the parameters to verify this.

Gateway priority orders queued admissions; it cannot preempt inference already running in Ollama. One reserved slot does not isolate GPU prompt processing. Existing measured TTFT/first-content contention and benchmark documentation remain applicable.

## Changed files

- jarvis-common: DelegationRequest and its English/Polish cardinality/negative-routing tests.
- jarvis-memory: ModelExecutionStage deterministic async route; MainModelIntegratedToolTrigger capability distinctions; ToolCallingStage reserves helper routing context to Core.
- jarvis-tools: NativeToolLoopService scoped Companion catalog, bounded submission profile, distinct-receipt completion, state-aware ongoing-work guard, and workflow/cardinality/state regression tests.
- jarvis-api: Phase2DelegationTest same-request retry versus three different goal regression.
- jarvis-ollama: OllamaProvider final HTTP catalog/profile/options diagnostics.
- scripts/companion/verify_packaged_core.py: authenticated Windows WebSocket one/three-agent production-path regression, isolated JAR, stale prompts, held concurrent workers, interactive chat, individual management and lifecycle replay checks.
- API_CONTRACT.md: corrected group submission DELEGATED semantics; event/REST shapes and contract version remain 1.5.0.

## Validation

Final results and tested artifact hash are recorded in PRODUCTION_DELEGATION_FIX_RESULTS.json. The full Maven reactor includes existing Calendar, Gmail, Knowledge, workflow, context/deadline, gateway and API regression tests. The packaged smoke uses the actual Spring production JAR and local HTTP fake Ollama, not mocked controllers, schedulers or task storage. It creates one task and then three sequential submissions from ONE normal authenticated WebSocket message; all three hold real gateway leases concurrently until the test releases them. It verifies all IDs in the user-facing acknowledgment, three running workers before release, a further ordinary chat response, instruction delivery, independent cancellation, completion state/events, stable num_ctx and actual submission num_predict/think.

These are mocked-inference integration checks. No production Ollama/GPU verification or Ubuntu deployment was performed here. Real model adherence and production latency must be checked during the manual controlled deployment; prior operator hardware benchmarks are preserved, not replaced by local test timings.

## Manual deployment and verification

1. Fetch feature/companion-core and verify the delivered commit. Build the production JAR using Java 21 and the project's Maven build; record its SHA-256. Stop the previous Core process using the existing operational procedure and start the verified artifact using the existing configuration/credentials. Do not deploy a previously cached JAR or dependency set.
2. Merge the existing Phase 2 application-companion-phase2.yml overlay without duplicate jarvis keys. Preserve the validated Ollama service environment and model. Keep memory/dreaming disabled. No Windows contract update is required.
3. Confirm /api/health is READY, contractVersion is 1.5.0 and metrics report four configured slots, one reserved interactive slot and 250 ms spacing. Confirm Companion tool registration in startup logs.
4. In ONE normal Windows chat message request: “Uruchom trzech niezależnych agentów w tle: pierwszy sprawdza A, drugi analizuje B, trzeci porównuje C. Nie czekaj na ich zakończenie.” Use concrete independent safe goals in place of A/B/C.
5. Correlate request IDs: the submission HTTP catalog must have the four Companion functions, profile tool-selection, think low, num_predict 768 and num_ctx 24576. No Calendar/Google Tasks action should execute. The final acknowledgment must contain three different real IDs; DELEGATED must occur only after all three submissions.
6. Verify three tasks have separate executionConversationId values. While all three run, send an ordinary chat message, inspect each task, instruct one and cancel another. Verify real task.started/task.completed/task.cancelled events/results and reconnect replay. Record per-call token counts separately from cumulative totals and prompt processing separately from generation when Ollama supplies metrics.
7. Measure interactive latency under long prompts on the production GPU. Queue priority cannot interrupt in-flight prompt processing; use the previously measured latency expectations. If schemas or deployed hashes differ, capture the actual catalog/parameters before attributing absence to the model or changing GPU configuration.
