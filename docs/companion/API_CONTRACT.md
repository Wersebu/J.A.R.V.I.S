# Companion API contract 1.2.0

Published 2026-11-24. Backend owns state. Additive to existing APIs; legacy /ws/jarvis is unchanged. Version changes are recorded here and in CONTRACT_VERSION.txt. Major = incompatible; minor = additive; patch = clarification. Clients ignore unknown fields/events.

## Availability
Initial integration target: /api/v1/companion REST and /ws/companion. See CODEX_STATUS.md for verified implementation. Reserved events below are NOT capability claims. No deployment has occurred.

## Authentication
Reuse POST /api/v1/auth/login {"email":"...","password":"..."}; response {"accessToken":"...","expiresAt":"RFC3339","user":{"id":"...","email":"...","displayName":"...","role":"..."}}. Bootstrap is existing /api/v1/auth/bootstrap-status and /bootstrap; never bootstrap automatically. Send Authorization: Bearer TOKEN for REST and WebSocket upgrade; existing interceptor also supports access_token query parameter but desktop should use headers to avoid logging tokens. 401 requires login; no refresh endpoint. GET /api/v1/auth/me and POST /api/v1/auth/logout remain. HTTPS/WSS outside trusted local development. Every task, snapshot, event and command is owner-scoped; another user's ID returns 404.

## REST integration target
All paths below relative to /api/v1/companion. JSON bodies/responses, authenticated.
- GET /capabilities -> {"contractVersion":"1.2.0","tasks":true,"voiceStreaming":false,"durableHistory":false} (additional flags permitted).
- POST /tasks -> 202 Task. Body {"message":"Research this topic","role":"RESEARCH","priority":20,"timeoutSeconds":120,"maxAttempts":1}. Roles CONVERSATION, TASK, RESEARCH, MEMORY, PROACTIVE. Only TASK/RESEARCH supported initially. Priority 0..100, lower first, default 50; interactive voice reserved 0. Timeout 1..3600 seconds includes queue wait. maxAttempts 1..3; default 1; automatic retries only for explicitly retry-safe handlers (initial chat handler rejects >1 to avoid repeating tool side effects). Client must not blindly retry POST: no idempotency key yet.
- GET /tasks -> array of retained Task, newest first.
- GET /tasks/{taskId} -> Task or 404.
- POST /tasks/{taskId}/cancel -> Task (200); idempotent for terminal tasks. A cancellation request is not completion. State becomes CANCEL_REQUESTED, then CANCELLED when the worker exits. Queued cancellation can become CANCELLED immediately. No promise to undo external side effects.
- GET /state -> {"contractVersion":"1.2.0","tasks":[Task],"activeProcesses":0}. Count QUEUED/RUNNING/CANCEL_REQUESTED. Snapshot reflects server state; indeterminate progress is null.
- GET /events?afterSequence=0 -> {"resetRequired":false,"events":[Event],"latestSequence":0}. Polling fallback for the same stream. Out-of-range cursor returns resetRequired=true and client must replace state via /state. Sequence scoped to user and server process.

Task shape: {"taskId":"UUID","role":"TASK","state":"QUEUED","priority":50,"progress":null,"createdAt":"RFC3339","updatedAt":"RFC3339","result":null,"error":null}. Result/error are nullable strings. Message and internal prompts are not echoed in snapshots. Terminal states COMPLETED, FAILED, CANCELLED, TIMED_OUT are immutable. Transitions QUEUED -> RUNNING -> COMPLETED|FAILED; QUEUED|RUNNING -> CANCEL_REQUESTED -> CANCELLED; timeout -> TIMED_OUT only after worker exits. Timeout/cancel interrupts are cooperative; an uninterruptible worker retains its slot and CANCEL_REQUESTED state until it stops. Progress 0..100 only when measured; null means unknown. Initial handler reports null throughout.

## WebSocket
New endpoint /ws/companion. Server sends system.connected with payload {"contractVersion":"1.2.0"}. Client command {"type":"state.sync"} -> system.snapshot payload identical to GET /state; {"type":"events.resume","afterSequence":42} -> replay retained events followed by system.resumed {"latestSequence":43}; replay gap -> system.reset_required, then request snapshot. No task submission over this socket initially; use REST. Unsupported/malformed commands -> system.error; no operation succeeds from receipt alone.

Event envelope: {"eventId":"UUID","protocolVersion":"1.2.0","timestamp":"RFC3339 UTC","sequence":1,"type":"task.created","taskId":"UUID","payload":{Task}}. taskId nullable for system events. task.created/task.started/task.cancel_requested/task.completed/task.failed/task.cancelled/task.timed_out carry a full Task. task.progress reserved until measurable progress exists. Control frames use sequence 0 and are not replayed; only state mutation events advance sequence. Deduplicate eventId; process replay by increasing sequence. Disconnect does not cancel tasks. Retry connection with jittered exponential delay 1..30s; stop on 401 until reauthenticated. Reconnect requests events.resume with last applied sequence. On server restart or retention gap replace state; process-local tasks/history are lost on restart in initial release. Fetch snapshot before reconnect subscription or after reset; merge events by updatedAt and terminal-state immutability. Server snapshot/control and live sends are serialized; client must buffer live events during synchronization. Retention bounded and capability advertised. Use WebSocket ping/pong for liveness; no success inference from ping.

## Conversation / voice compatibility
Continue using existing /ws/jarvis for conversation: CHAT_RESUME, CHAT_CANCEL and CHAT_STEER remain legacy commands with legacy seq frames; do not mix envelope versions. Background tasks use independent generated conversation IDs. The shared Ollama permit remains authoritative and defaults to one inference at a time on existing hardware; asynchronous tasks do not imply parallel GPU inference.
Existing GET /api/v1/voice/status advertises STT/TTS. POST /api/v1/voice/transcribe?fileName=recording.wav consumes raw WAV bytes and returns {"text":"..."}; POST /api/v1/voice/speak {"text":"..."} returns audio/wav. Provider unavailable returns 503 {"error":"..."}. Initial companion does not accept binary audio WebSocket frames or claim streaming STT. Client uses push-to-talk/VAD, sends completed recording, streams chat through legacy channel, and requests TTS per complete sentence. Client owns playback queue and echo prevention; on barge-in stop audio, discard queued sentences, and send legacy CHAT_CANCEL. No microphone recording before explicit opt-in. Wake word runs locally if enabled. voice.state values reserved: IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING, INTERRUPTED, MUTED, ERROR. Only advertise server streaming after provider support is verified and contract extended.

## Reserved event families
assistant.state (IDLE/THINKING/SPEAKING/ERROR), assistant.message.delta, assistant.message.completed, voice.state, memory.updated, proactive.message, system.metrics are reserved, not yet emitted by companion. Future payload schemas require an additive contract version before integration. No raw secrets, memory contents or internal reasoning in telemetry.

## Examples
POST /tasks {"message":"Compare sources for my project","role":"RESEARCH","priority":30,"timeoutSeconds":180,"maxAttempts":1}
202 {"taskId":"953aa5a0-8828-414d-aa77-b2394850b826","role":"RESEARCH","state":"QUEUED","priority":30,"progress":null,"createdAt":"2026-11-24T22:45:00Z","updatedAt":"2026-11-24T22:45:00Z","result":null,"error":null}
Event {"eventId":"953aa5a0-8828-414d-aa77-b2394850b827","protocolVersion":"1.2.0","timestamp":"2026-11-24T22:45:01Z","sequence":2,"type":"task.started","taskId":"953aa5a0-8828-414d-aa77-b2394850b826","payload":{"taskId":"953aa5a0-8828-414d-aa77-b2394850b826","role":"RESEARCH","state":"RUNNING","priority":30,"progress":null,"createdAt":"2026-11-24T22:45:00Z","updatedAt":"2026-11-24T22:45:01Z","result":null,"error":null}}

## Change log
1.2.0: additive atomic latestSequence cursor in /state and system.snapshot. After replacing state, resume/poll from that snapshot cursor.

1.1.0: additive serverInstanceId in system.connected and /state. Send it in events.resume; mismatch forces reset, including when new server sequence overlaps an old cursor. Client must compare instance IDs on every connection, discard old state on change, and request state.sync. Omitted ID remains compatible but cannot detect all restart overlaps. Retention: 512 events per owner; 1000 tasks globally; 128 active tasks globally; 1024 process-local owners. Admission returns 429 at capacity. Invalid task requests return 400. REST /state returns serverInstanceId for polling clients, which must compare it each poll. No automatic eviction of owner journals.


1.0.0: initial additive integration target; legacy APIs unchanged. Streaming voice, durable history, automatic memory and proactive features are not promised by this release.
