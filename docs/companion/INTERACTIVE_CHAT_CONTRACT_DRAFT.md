# Interactive chat concurrency contract — candidate for 1.6.0

Status: Core candidate implemented; awaiting Claude/Windows response and joint acceptance. This is not a completed release or deployment. Existing Companion handshake remains 1.5.0 until version coordination is resolved; schemas here describe the proposed chat extension, not a retroactive change to old fixtures.

## Identity and multiplexing

Windows generates a UUID requestId once before send and keeps the exact payload for retry. Core canonicalizes UUID casing. Missing requestId is supported for legacy requests: Core generates it before acceptance and returns it in CHAT_ACCEPTED. Missing conversationId is generated before acceptance as well. All pipeline diagnostics and persisted messages use that same requestId. Authentication binds ownership independently of all client IDs; a different owner cannot steer/cancel/resume it. Reuse of an ID for another payload is rejected.

Multiple independent conversations for the SAME owner run concurrently on one or several /ws/jarvis connections. Every NEW normal chat payload means an independent request, even while busy. Only an explicit CHAT_STEER is an instruction. Same-owner/same-conversation requests are accepted separately and execute FIFO; the next turn cannot read/build/write history until the previous turn's pipeline and terminal frame complete. There is no safe parallel execution against one mutable history; fork to another conversation for that behavior. REST chat uses the same history gate; independent Companion execution uses its own conversation ID.

## Frames

Every run frame has streamScope=REQUEST, requestId, conversationId and a monotonically increasing seq>=1 local to THAT request. Never deduplicate by conversationId+seq. Additional properties are permitted. Existing CognitiveEvent payloads retain event/status/message/metadata; transport lifecycle frames use type.

- CHAT_ACCEPTED: state ACCEPTED, server has registered the request identity.
- CHAT_QUEUED: state QUEUED, reason CONVERSATION_ORDER, queuePosition. Waiting precedes history access.
- CHAT_RUNNING: state RUNNING, pipeline may execute. This does not mean inference is admitted.
- event INFERENCE_QUEUED: status QUEUED, metadata.reason INFERENCE_CAPACITY when slots are exhausted, otherwise GATEWAY_ADMISSION (reservation/resource/spacing constraints). metadata includes inferenceId and profileId.
- event INFERENCE_STARTED: status ADMITTED and actual queueWaitMs. Admission is not proof of GPU prefill/token generation.
- HEARTBEAT: same identity/seq as all other run frames; elapsedMs, lastEvent and lastSeq.
- COMPLETED / CANCELLED / ERROR: exactly one terminal frame; all preceding request events retain their identity. DELEGATED is an inner tool-loop outcome and is not a task-completion claim.

CONNECTED, PONG, rejected ingress/control replies without a resolved run, CHAT_RESUMED and replay-gap/cursor responses are connection/control frames, seq=0 where applicable. Global JARVIS_LOG and owner background diagnostics are not canonical request frames; do not treat them as answer tokens or infer request ownership from the active view. Unknown additive fields/events must be ignored safely.

## Explicit controls

Send {type:CHAT_CANCEL, requestId:UUID, conversationId:ID}. Core replies CHAT_CANCEL_ACCEPTED/REJECTED. Cancellation can target a conversation-order wait or a gateway wait as well as active inference. A request cancelled before history/pipeline starts does not create a user/assistant history turn. A request already persisted may have a user turn without an answer; cancellation does not delete real input. It never cancels a sibling request. The terminal CANCELLED frame is authoritative; request acceptance does not prove the underlying transport has already stopped.

CHAT_STEER additionally carries message (1..4000 characters, bounded mailbox). Only the addressed running request can accept it; a queued request is not silently promoted into an instruction. CHAT_STEER_UNCONSUMED returns instructions that arrived too late; Windows may offer them as NEW independent requests using NEW IDs, never resubmit blindly as instructions. Instruction acceptance is not a claim that the agent has consumed it yet.

Legacy conversation-only controls are accepted only for the authenticated owner's unambiguous active/recent request. Multiple accepted same-conversation requests require requestId; an ambiguity returns *_REJECTED. No control resolution ever uses another owner's conversation ID.

## Retry, reconnect and ordering

CHAT_RESUME carries requestId, conversationId and afterSeq. A new session attaches to the addressed retained run and receives frames after its own cursor. CHAT_RESUMED is a seq=0 control response, not an increment to the request cursor. A duplicate exact initial submission attaches/replays the existing run rather than starting another pipeline. Windows deduplicates repeated replay/live frames by requestId+seq.

Run replay is bounded (configured frame buffer, retention, and registry process lifetime), not durable exactly-once across Core restart. CHAT_REPLAY_GAP reports firstAvailableSeq/lastSeq before retained partial replay; Windows must reconcile durable conversation messages/task state and show a gap rather than pretend the entire stream was recovered. CHAT_RESUME_REJECTED reports a negative/future cursor; CHAT_RESUME_UNKNOWN reports expired/unknown/ambiguous identity. Do not automatically turn UNKNOWN into resubmission with a new ID: reconcile history first to avoid duplicate work.

Core serializes emission/replay per run and writes whole frames through the shared socket lock. Interrupting one inference worker must not interrupt a socket write or disconnect sibling runs. Server-side conversation admission is bounded (64 accepted turns per conversation, 1024 overall) and retained registry capacity is bounded (2048 runs); excess admission gets an explicit rejection/error, never silent loss.

## Capability discovery

The candidate /api/v1/companion/capabilities adds interactiveChat with requestIds=true, multiplexing=true, sameConversationPolicy=FIFO, requestControls=true, requestReplay=true and protocolRevision=candidate-1.6.0. Windows must feature-detect this object (absent on older Core), rather than enable concurrency from the old 1.5.0 handshake alone. Final version publication remains pending joint acceptance.

## Windows integration requested from Claude

Use one persistent transport receiver dispatching canonical frames by requestId, with per-conversation view state and per-request cursors. Switching views must not detach/cancel a run. Replace global sending/requestActive/single listener as detailed in WINDOWS_CHAT_CONCURRENCY_AUDIT.md. New question, Instruction and Stop are separate actions; same-conversation new questions visibly wait in Core FIFO. Display conversation order and inference capacity waits separately. Keep the current visible client queue until this refactor and joint tests pass. Dynamic gateway metrics remain authoritative; no hardcoded slot allocation in Windows.

Please return WINDOWS_CHAT_CONCURRENCY_RESPONSE.md confirming IDs, frame names/scope, legacy behavior, FIFO UX, replay-gap handling, the coordinated minor-version publication and test status. No Windows implementation or joint acceptance is claimed by Core alone.

## SSE compatibility boundary

This multiplexing/replay contract is for /ws/jarvis. Existing standalone HTTP/SSE streams retain their own transport association/server-generated IDs and do not inherit WS idempotency. After sending a possibly accepted WS request, reconnect/resume/reconcile it; do not automatically re-execute it through an HTTP/SSE fallback. Keep legacy fallback isolated/serialized until separately coordinated.

Parser fixture: schemas/1.6.0-draft/examples/multiplexed-chat.json is illustrative, not a captured production/performance record. Advance each request cursor for every canonical sequenced frame, even when its additive event type is ignored; deduplicate by requestId+seq before rendering/speaking.
