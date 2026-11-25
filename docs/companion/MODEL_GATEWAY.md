# Model Gateway

The existing OllamaRequestCoordinator now implements the common ModelGateway boundary. It remains the only admission permit around provider generation/tool turns. No second semaphore was added. Release is keyed by unique lease ID and idempotent, so a double close cannot free another request's capacity.

Asynchronous task execution means several task workers can wait, perform I/O or run tools independently. Parallel inference means several HTTP generation requests hold gateway leases at the same time. The latter is configurable, defaults to one, and does not imply Ollama itself executes those requests simultaneously. Its current daemon settings, loaded models, context allocation and available VRAM determine actual overlap. No production daemon setting was changed.

Configuration (see config/companion-gateway.example.yml, not loaded automatically):
- jarvis.model-gateway.concurrency: 1 by default; 2 and 3 are tested; upper bound 64 leaves room for future resource pools.
- reserved-interactive: 1 by default, ignored with concurrency=1. At concurrency=2 this admits at most one background inference, leaving one slot for conversation. At 3 it admits two background requests plus a conversation. Set 0 explicitly to test all-background saturation. Reserved capacity is deliberately not lent to background work.
- max-waiting: 128, queue-timeout-millis: 300000. Queued work is interruptible; admission capacity and timeout fail explicitly. Task deadlines also interrupt waiting requests. Active inference retains its lease until provider execution exits; cancellation never falsely frees capacity.
- minimum-free-heap-bytes: optional JVM pressure floor, default 0. This is not GPU telemetry.
- context-token-budget: optional conservative sum of configured per-request context allocations, default 0 (off). Requests wait rather than exceed it; a budget smaller than one request is rejected at startup. Context length remains jarvis.ai.context-window, with jarvis.ai.reserved-output-tokens; these feed the existing context fitter and Ollama num_ctx. Do not multiply the production 100000-token setting by three without a measured VRAM budget.

Resource scheduling currently uses concurrency/reservation, heap pressure and configured context allocation. It does NOT query live GPU free memory or auto-discover GPUs. Real VRAM measurements must inform admission configuration. There is no promise of OOM prevention from heap checks alone. Existing provider errors fail the request; tools are not blindly retried.

Every background task has a fresh conversation ID; task context and user context remain independent. Conversation history is never shared by gateway leases. The current configured endpoint is jarvis.ollama.base-url, which can already point at a remote Ollama host. The ModelGateway interface and instance-local lease state allow future per-endpoint/GPU resource-pool registration without adding nested locks; automatic multi-endpoint routing is not implemented in this milestone. No additional GPU is assumed.

Benchmark before raising the limit. Startup warmup, embeddings and tool-triggered vision requests also use the same gateway in the Spring application. Continuation calls keep their existing generation lease rather than acquiring a nested permit. Avoid benchmarking during startup/indexing to keep the measured workload identifiable. The wiring test mocks HTTP to avoid accidental real startup inference.
