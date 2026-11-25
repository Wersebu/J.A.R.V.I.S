# Architecture audit and progress

## Phase 0 completed
Backend branch feature/companion-core created from claude/sharp-thompson-any8lk at existing HEAD; backend initially clean. Parent workspace and Windows contain extensive pre-existing changes: untouched. No AGENTS.md found in backend. SHARED contract 1.0.0 published locally; no remote push/deployment.

Reuse: Java 21 / Spring Boot 3.3.7 Maven reactor; ChatService streaming and per-user context; ChatRunRegistry bounded replay and cancellation; JarvisAuthInterceptor and WebSocket authentication; VoiceService HTTP STT/TTS; CognitiveEventBus; OllamaRequestCoordinator serial permit with interactive priority; MemoryAgentService, asynchronous memory jobs, cognitive stores and embeddings; SearxngWebSearchClient and research quality evaluator; existing diagnostics/model benchmark controller.

Gaps: no general owner-scoped background task registry or companion protocol. Memory dependency is SQLite, not assumed PostgreSQL/pgvector despite vision infrastructure description: inspect actual deployment before any migration. Model coordinator serializes inference; priority does not preempt active inference. Existing STT/TTS are recording/request based. Durable task persistence, inference routing, semantic memory safeguards, idle consolidation, iterative web extraction/security, proactive policy and hardware benchmarks require further stages.

## Implementation plan
1. Publish contract, schemas and examples (done).
2. Add bounded task scheduler, owner-scoped history, cooperative cancellation/timeouts and event replay; connect REST/WebSocket and test mocked clients.
3. Extend model routing/admission and role orchestration using existing permit; integrate streaming conversation and voice without duplicate inference gates.
4. Audit memory deployment; semantic extraction/dedup/contradiction and auditable optional dreaming.
5. Research retrieval/security and event-driven proactive policies.
6. Integration tests and real hardware benchmarks only against explicitly available nonproduction services.

No production actions authorized. No added GPU assumed.

## Phase 1 foundation implemented
Bounded owner-scoped process-local Task Manager and companion REST/WebSocket transport. Priority queue defaults to two worker threads but retains one Ollama inference permit. Task inference receives BACKGROUND classification; interactive waiters win the next permit. Cancellation and deadlines are cooperative and do not release capacity before worker exit. No unsafe automatic retries. No synthetic progress. HTTP and socket authentication reuse existing services. Journal and snapshots support reconnect and server instance change detection.

New code does not replace the existing Brain router/AIProvider selection. A full configurable Model Gateway and Agent Orchestrator remain unfinished; TASK and RESEARCH both use the current chat pipeline with independent conversation IDs. RESEARCH is a logical classification, not a guarantee of deep research mode. Voice priority applies to waiting interactive requests, not preemption of an already running inference.

## Subsequent-phase audit
Conversation: existing ChatRunRegistry and ChatService already stream and support steer/cancel/resume; compatibility tests cover these paths. Existing HTTP VoiceService is reused unchanged. Sentence TTS scheduling, VAD, echo prevention and playback are client concerns in the fallback contract. Server streaming audio and personality announcements remain unfinished.
Memory: DefaultMemoryJobService already queues semantic AI extraction asynchronously with user context; DefaultMemoryAgentService has CREATE/UPDATE/DELETE and token-overlap deduplication. Knowledge files are also part of the current durable memory architecture; do not assume the legacy semantic store is authoritative. Existing prompt lacks a strong sensitive-data exclusion and deduplication is heuristic; expiration, contradiction audit and idle dreaming require dedicated work before enabling new autonomous retention. No memory defaults changed.
Research: DefaultResearchEngine already performs iterative model-driven work; WebSearchTool and HttpWebPageReader provide source retrieval/links. Current HTTP reader validates only the original URL and follows redirects automatically, so redirect destination checks, DNS rebinding defenses and response-byte limits are unresolved security work before expanding crawl capabilities. Do not enable broader crawling yet. Crawl4AI/Playwright have not been installed or evaluated against live infrastructure.
Proactivity: no new engine or polling was enabled. Personality-driven event announcements, per-user policy, quiet hours and DND remain future implementation.
Performance: real 1/2/3-request GPU benchmarks require available nonproduction Linux inference/metrics services; none were contacted. No claim of measured VRAM or voice latency.

## Validation
Targeted compatibility run: 56 tests passed (6 memory, 17 API including 7 companion tests, 33 Ollama). Additional admission/replay/cancellation coverage added afterward, with final rerun recorded in SHARED/CODEX_STATUS.md.
Broad reactor run: jarvis-tools 499 tests, 498 passed, 1 failed: NativeToolLoopServiceStoreAuditGovernanceTest.fullRealProductionScenarioReachesScheduledWithAllTwentyThreeRecords at line 338, expected Polish schedule answer but got context-checkpoint failure. No jarvis-tools source files changed. This is an unresolved regression-suite blocker, not attributed to a baseline without reproducing at original HEAD. Initial sandbox run had loopback errors; unrestricted local mock-server run removed those errors.

Run with installed JDK21. This machine requires preloading cached Byte Buddy agent for Mockito; dynamic attach pipe is restricted. Maven offline resolution uses a process-local lock factory because the shared dependency cache is read-only in the sandbox. No dependencies were downloaded.

## Published milestone — contract 1.2.0
Final focused run: 12 tests passed (9 companion API/scheduler/socket plus 3 Ollama admission tests). Version accessor avoids stale Java compile-time constants across incremental builds; handshake verifies current protocol version. Contract copies compared byte-for-byte and JSON artifacts parsed, but full JSON Schema validation was not run because no schema validator is installed. git diff --check passed.

Phase 0 complete; Phase 1 task/transport/admission foundation complete, full Agent Orchestrator/Model Gateway still pending. Phases 2–6 remain partly or fully unfinished as described above. This milestone is not acceptance of the complete Companion Update.

## 2026-11-25 — configurable gateway and live integration milestone
- Existing inference gate implements ModelGateway; concurrency defaults to 1, tested at 2/3, with reserved interactive capacity, bounded admission/deadlines, heap/context budgets, unique idempotent leases and cooperative cancellation. Generation, tool turns, vision, embeddings and startup warmup share it. Independent task conversation IDs remain. No production Ollama setting changed; actual GPU overlap is unmeasured. See MODEL_GATEWAY.md and BENCHMARKS.md.
- Contract 1.3.0 and schemas published before optional personality announcement implementation. Lifecycle state gates text emission, completion requires confirmed success, stale generated acknowledgments/progress are discarded. Cached generated acknowledgments can be immediate after the first generation; first-call model latency remains possible. No synthetic percentages.
- Scheduling regression and 3 Gmail fixture failures reproduced at baseline 1655d71, then repaired in test setup/fakes without weakening production safety checks. Full application wiring test now mocks HTTP so it cannot invoke real startup inference. See REGRESSION.md.
- Web reader hardened with connection-level validated DNS answers, redirect refusal, credential/nonpublic-IP checks, bounded bodies and total deadlines. Rebinding, redirect and oversized-response tests added. No broader crawler enabled.
- SQLite/knowledge-file reality and backward-compatible PostgreSQL/pgvector shadow/outbox proposal documented in MEMORY_STORAGE.md. No migration executed.
- Standalone isolated integration launcher running on loopback 18080 with generated ignored test credentials, simulated inference, in-memory task state and real companion transport. HTTP/WS login/snapshot/cancel/completion/resume smoke tests passed. See INTEGRATION.md and SHARED/INTEGRATION_ENVIRONMENT.md.

Validation: full Maven reactor through jarvis-core: 940 tests, 0 failures/errors/skips, BUILD SUCCESS (companion-all-verified.log). Python benchmark fake-stream unit test: 1 passed. All versioned JSON schemas checked and 5 fixtures validated against draft-2020-12 with format checks; shared/backend contract copies identical. git diff --check passed. No production benchmark, deployment or main merge.

Remaining limits: actual Linux GPU/latency benchmark awaits an approved execution window; automatic multi-endpoint/GPU routing is future work behind the gateway boundary; resource checks do not measure live VRAM; task history stays process-local; first-use generated acknowledgment is not latency-bounded; full voice streaming/memory/proactivity acceptance criteria from the original update remain later work. Current requested storage reconciliation is a proposal, not a deployed PostgreSQL adapter.

Final review: a saturated background wait queue no longer rejects an interactive request when its reserved inference slot is free. Added a dedicated regression test; full reactor now passes 941 tests with zero failures/errors/skips (companion-reservation-verified.log). IANA special-purpose address exclusions were reviewed and extended; all 8 Web Reader tests passed after that final policy update (companion-address-policy-tests.log). The isolated integration server continues on 127.0.0.1:18080; no production services were changed.
