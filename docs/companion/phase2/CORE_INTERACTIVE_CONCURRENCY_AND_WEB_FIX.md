# Core interactive concurrency and ordinary web research — candidate implementation

Date: 2026-11-26. Base commit: 56a43d97abcd4c507cbe06801c60c3dce067f225 plus this review candidate on feature/companion-core. Not deployed. Release is NOT complete: Claude/Windows contract response and client integration remain pending. See INTERACTIVE_CHAT_CONTRACT_DRAFT.md and INTERACTIVE_CONCURRENCY_WEB_RESULTS.json. Windows files were not modified.

## Coordination with Claude

Read WINDOWS_CHAT_CONCURRENCY_AUDIT.md and WINDOWS_CONCURRENCY_REFACTOR.md. Posted CORE_CHAT_CONCURRENCY_PROPOSAL.md, explicit CORE_TO_CLAUDE_INTERACTIVE_CONFIRMATION.md (Stage 2 prerequisites confirmed), and implementation checkpoints in CODEX_STATUS.md, requesting WINDOWS_CHAT_CONCURRENCY_RESPONSE.md. Published the exact candidate contract and JSON schemas under schemas/1.6.0-draft. At the time of this report no response file or newer Claude status was present. Do not treat a Core-only mock-client run as joint Windows acceptance.

Candidate capability discovery adds interactiveChat to /api/v1/companion/capabilities: requestIds/multiplexing/requestControls/requestReplay true, sameConversationPolicy FIFO, protocolRevision candidate-1.6.0. The existing Companion handshake and schemas remain 1.5.0 until the coordinated minor-version publication. This lets Claude gate the client refactor by an actual capability object instead of assuming every 1.5.0 server has multiplexing.

## Interactive concurrency audit and confirmed defects

| Layer | Baseline behavior | Candidate behavior |
| --- | --- | --- |
| WebSocket receive | Container serializes frame callbacks, but handler quickly dispatches each chat to a cached executor. Different conversations were already able to execute concurrently. | Receive callback remains short; one socket accepts multiple independent UUID-tagged requests. No implicit steer. |
| ChatRunRegistry | Keyed by owner+conversation. A second same-conversation request replaced the still-running entry and lost its replay/control identity. | Keyed by owner+requestId, with payload conflict checking and retained retry attachment. Each request owns its own frame sequence. |
| ChatRunControl | Keyed by conversation alone; concurrent runs overwrote control handles and another owner could collide on a conversation ID. | Owner+requestId in interactive scope; owner+conversation for isolated legacy/internal scope. Explicit controls resolve only an owned registered request. |
| ChatService | No global lock, but also no whole-turn conversation ordering guard. | Ingress FIFO reservation per owner+conversation before pipeline/history access. REST chat shares the gate. Other conversations are independent. |
| CognitivePipeline | Request execution is synchronous on its worker, using thread-local event/diagnostic contexts. No global pipeline executor serialization. | Authoritative ingress requestId is propagated into the pipeline, diagnostics and persisted messages; cleanup runs even when cancellation aborts an event sink. |
| History | Individual persistence calls did not protect the entire load-prompt-user-answer sequence from two competing turns. | Whole-turn gate protects that sequence and holds the next WebSocket turn until the preceding terminal frame. Separate owner/conversation histories remain independent. |
| Gateway | Existing shared admission limits/priority/reservation could queue requests; no explicit canonical inference-queue event was emitted on entry. | INFERENCE_QUEUED reflects actual waiting and reason, INFERENCE_STARTED reflects actual admission, each with inferenceId/profileId. Existing limits and scheduling stay unchanged. |

A production-JAR regression initially reproduced loss of the cancellation terminal while other requests shared the socket. Preserving an interrupted inference worker's flag during a socket write can disrupt that shared transport. Both socket-send paths now temporarily clear/restore the flag around the write; cancellation remains set in the run control. The final JAR test cancels a real gateway wait and still completes all sibling conversations on the same connection. A unit test verifies that an interrupted worker does not pass its interrupt into the shared socket write.

## Request lifecycle and history guarantees

See the candidate contract for every payload. CHAT_ACCEPTED establishes authoritative requestId and conversationId; every canonical run frame carries streamScope=REQUEST and seq>=1 local to that request. New busy-time questions are separate runs. Only CHAT_STEER is an instruction, and only CHAT_CANCEL cancels a selected run. Same-conversation questions are accepted separately and wait FIFO with CHAT_QUEUED reason CONVERSATION_ORDER; they do not execute in parallel against mutable history. Use another conversation for independent parallel execution.

The gateway emits INFERENCE_QUEUED with reason INFERENCE_CAPACITY when all slots are occupied, or GATEWAY_ADMISSION for other reservation/resource/spacing constraints. CHAT_RUNNING is pipeline execution, not proof of GPU admission. Queue state is real, not inferred from elapsed time or model prose. Admission cannot preempt inference already running inside Ollama.

Canonical frames, heartbeats, terminal frames, controls and replay use the same owned identity. Controls without a resolved run and connection/global diagnostics are explicitly outside the replay stream. Conversation-only legacy controls/resume are rejected when ambiguous. Exact retained retries replay/attach, not execute again; mismatched payload reuse is rejected. Replay has explicit gap and invalid-cursor responses. This is bounded in-memory idempotency across the configured retention, not durable exactly-once delivery after restart.

## Ordinary web research investigation

The prior explicit Companion fix did not fix ordinary web research. Confirmed code-path defects:

1. The lightweight detector has no weather vocabulary; the freshness evaluator also lacked weather, and the GTA VI phrase “kiedy wychodzi” need not match release-date terms. A main-model decision could therefore answer without a live lookup or enter a broad/wrong capability route.
2. Logical-budget selection can trim a large registry using relevance/alphabetical ranking; the prior dedicated route pinned Companion, not ordinary public web schemas. A stale topic/workflow could narrow the available tools further. Registration alone did not prove the schemas reached HTTP.
3. WebSearchQualityEvaluator treated exchange-rate requests as marketplace-price evidence. Its requiresMarketValue path deliberately refused to accept search snippets as verified product prices, forcing unnecessary recovery/page reading even for a sourced USD/PLN reference quote. Existing marketplace behavior must remain unchanged.
4. After a model transport failure the generic tool-loop recovery could invoke a separate tool-less model turn. Public live research now fails concisely instead of generating an ungrounded substitute; a regression asserts one failed inference with no synthesis retry.

The historical incident's exact production HTTP catalog and SearXNG health were not supplied, so absence of actual registered web beans or a specific production provider outage is NOT asserted. Source and final packaged Spring tests confirm WebSearchTool/SearxngWebSearchClient registration and actual HTTP schema/transport behavior.

## Web routing and evidence implementation

PublicWebRequest recognizes the tested Polish/English public-live questions while excluding local workspace/connected-system inspection signals. ModelExecutionStage routes these directly to TOOL_REQUEST, so editable identity does not suppress a Core capability or spend an extra inference deciding whether an explicit public lookup needs tools. NativeToolLoopService selects only web__search_web, web__read_web_page and output__read for this route, independent of a stale workflow/provider hint. It rejects unrelated Calendar/Google Tasks calls. The global max-offered-tools limit remains 16.

Actual HTTP tool schemas retain numeric maxResults/targetCount, string query/profile/language/timeRange/category and an exact URL for READ_WEB_PAGE. The production-path fake Ollama asserts the final three-function catalog. The real Core HTTP adapter sends GET /search?q=...&format=json to the configured SearXNG endpoint and normalizes the JSON results. Ordinary research does not use SEARCH_MARKETPLACE. Currency detection excludes commodity/gold/crypto requests; currency-specific quality acceptance no longer requires marketplace price observations. Existing marketplace/price regression tests remain green.

The compact Core-owned research instructions require actual tool evidence, source URLs and source dates; distinguish weather observation versus forecast, dated reference rates versus live trading, publication/observation time versus retrieval time, and publisher release announcements. Relevance/entity/value gates still apply. Empty/unrelated evidence cannot satisfy live evidence; unavailable providers return a concise failure without unrelated tools or invented current facts. The integration test intentionally returns a fact-shaped model answer after empty search results and verifies it never reaches the user.

## Automatic independent research delegation

When the original message contains two or three distinct, self-contained public-web questions separated by question marks, semicolons or lines, Core may automatically create independent RESEARCH tasks without the user naming Companion agents. The heuristic rejects dependent phrases such as “na podstawie”, “następnie” or “based on”, oversized inputs, duplicate questions and explicit no-delegation wording. Explicit agent delegation retains its existing model-owned goal decomposition. Background tasks/helpers cannot recursively apply automatic delegation.

Each automatically submitted goal is compact and self-contained; Core uses the real Companion submission tool/scheduler and persistent receipt boundary, not fabricated IDs. The parent uses zero model calls to decide or narrate this deterministic split. It acknowledges all actual task IDs and releases the chat without waiting for results. Accepted siblings remain tracked if another submission fails; no uncreated remainder is claimed. Results arrive through the existing linked Task Center/history and task completion events, not a fabricated combined answer appended to the chat. Ambiguous/dependent or unsplit requests stay in the normal tool loop; this heuristic is not a general semantic planner.

## Provider configuration and availability

Source defaults are jarvis.web-search.enabled=true, base-url=http://127.0.0.1:8888, connect-timeout=2s, read-timeout=8s. Deployment overrides/environment may differ; production reachability and enabled JSON output have NOT been checked from this Windows workspace. No production credentials were read and no remote deployment was performed.

On the Core host the operator can run scripts/companion/check_web_provider.py --base-url http://127.0.0.1:8888 (or the actual configured URL). It performs three read-only HTTP JSON searches, outputs status/array/count/latency only, invokes no inference and changes no configuration. HTTP 403 commonly requires checking the actual configured SearXNG JSON-output policy; HTTP 200 without a results array is not accepted as valid protocol readiness. Confirm networking from the Core process/container namespace, not only a desktop browser. Search content/date relevance still requires separate validation even if transport is healthy.

## Validation

Full Maven reactor: 987 tests, zero failures/errors/skips. Final API recompilation and targeted regression cover request concurrency, replay, owner control isolation, interrupted socket writing and the production servlet context. One earlier run hit a transient Windows temp-directory SQLite cleanup failure in an unchanged vault test; the subsequent full run passed without skipping or modifying vault code.

Final production JAR smoke with local fake Ollama and SearXNG HTTP services passed:
- Four concurrent interactive conversations of ONE authenticated owner on ONE socket.
- A fifth request reporting actual gateway-capacity waiting and independent cancellation.
- Same-conversation FIFO with USER/ASSISTANT/USER/ASSISTANT durable ordering and exact persisted requestId correlation.
- Queued cancellation before history admission; explicit targeted instruction and correlated unconsumed-instruction return.
- Malformed frame rejection without disconnecting sibling requests.
- Idempotent resend without another history turn; request-specific reconnect replay and canonical frame IDs/sequences.
- Each exact weather / USD-PLN / GTA VI question through normal authenticated WebSocket chat, actual three-function HTTP catalog and Core->SearXNG HTTP JSON request.
- The combined three-question message automatically creating three distinct tracked tasks, with all three real sourced fixture results available on completion.
- HTTP 503 SearXNG failure and empty evidence rejecting fabricated current answers; HTTP 503 model failure produces no tool-less synthesis retry.
- Unchanged physical num_ctx=24576 on every inference.

The previous packaged Companion regression also passes on the SAME JAR: single/three explicit agents, early acknowledgment, three running background workers, ordinary chat availability, instructions/cancel/results and contract 1.5.0 compatibility. See INTERACTIVE_CONCURRENCY_WEB_RESULTS.json for artifact hash and exact scope. All factual answers in these tests are explicitly synthetic fixtures. No real Ollama/GPU, real weather/rates/release-date query, Windows UI refactor or joint client test is claimed.

## Changed implementation areas

jarvis-api websocket ChatRunRegistry/JarvisWebSocketHandler and tests; CompanionController capability discovery. jarvis-common ConversationTurns/InteractiveRequestContext/ChatRunControl/PublicWebRequest/CognitiveEventType and tests. jarvis-core DefaultChatService/PipelineContextFactory/DefaultCognitiveEventBus. jarvis-memory ModelExecutionStage. jarvis-ollama OllamaRequestCoordinator admission events. jarvis-tools NativeToolLoopService/InformationFreshnessEvaluator/WebSearchQualityEvaluator and the currency recovery expectation test. New packaged regression and provider-check utilities in scripts/companion; contract/schema/report copies in docs/companion and SHARED.

## Preserved configuration and release gate

Ollama/GPU configuration was not changed: OLLAMA_NUM_PARALLEL=4, FLASH_ATTENTION=1, KV cache q8_0, physical context 24576. Core retains four gateway slots, one reserved interactive slot, three Companion workers and 250 ms background submission spacing. Autonomous Memory, Dreaming, memory background-agent and background processing stay disabled. Multiple interactive requests compete for the SAME measured four-slot/GPU pool; queue priority does not preempt in-flight Ollama work or eliminate measured long-prompt contention.

Next required external step: Claude responds to the draft contract, implements/tests the per-request Windows dispatcher/view state and explicit New question/Instruction/Stop UX, then both sides perform joint isolated runs and agree the final minor-version publication. The currently audited Windows single-run client does NOT gain true concurrency merely because Core passes a mock-client test. Do not deploy or declare the release complete before that coordination gate. No automatic Ubuntu deployment or Windows modification was performed here.
