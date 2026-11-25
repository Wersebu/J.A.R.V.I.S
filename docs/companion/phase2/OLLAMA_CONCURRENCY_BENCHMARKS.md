# Ollama concurrency benchmarks — production server

Source: owner, measured on the production server. Date: 2026-11-25. Status: **real measured data**
(confirmed via benchmark timings and Ollama runner logs) — used as the factual basis for Model
Gateway / inference telemetry in the companion design. **Not** a simulated or projected figure.
Additional four-slot and long-prompt results are recorded below; the recommended initial physical context is now 24576.

## Hardware
- GPU: **RTX 4060 Ti 16 GB**
- CPU: Intel i7-10700K
- RAM: 128 GB
- Primary model: **GPT-OSS 20B** (fully on GPU in the initial 8K table; later sections identify partial offload)
- Whisper (STT): remains resident on the GPU, **984 MiB**

## Results — 8192 context tokens per slot
| Parallel requests | Aggregate throughput | Per-request generation | TTFT | Total VRAM |
|---:|---:|---:|---:|---:|
| 1 | 65.31 tok/s | 66.25 tok/s | 0.16 s | 13501 MiB |
| 2 | 99.30 tok/s | 50.80 tok/s | 0.30 s | 13713 MiB |
| 3 | 126.77 tok/s | 43.20 tok/s | 0.37 s | 13925 MiB |

## What this establishes (facts the design relies on)
1. **Real parallel inference exists.** The "shared Ollama permit defaults to one inference at a time"
   assumption in `API_CONTRACT.md` (§Conversation/voice compatibility) is superseded by measured
   multi-slot throughput. The companion must model **active inference as a plural, dynamic count**,
   not a single in-flight call.
2. **Concurrency is dynamic, not a fixed constant.** Capacity depends on model, context-per-slot and
   VRAM headroom (benchmarks extend to 4 slots / larger context). The client must **read the
   gateway's current capacity from Core**, never hardcode a slot number.
3. **VRAM telemetry is real and meaningful.** Total VRAM rises modestly per slot (~212 MiB/slot here:
   13501 → 13713 → 13925 MiB) with Whisper's 984 MiB resident alongside. A diagnostics VRAM row is
   grounded, once Core exposes it.
4. **Aggregate vs. per-request diverge.** Aggregate throughput rises with slots while per-request
   generation falls (66 → 51 → 43 tok/s) and TTFT grows (0.16 → 0.37 s). The diagnostics panel should
   show **both** aggregate gateway throughput and per-request figures, not conflate them.

## Telemetry these numbers imply Core should expose (additive `system.metrics`)
- `gatewaySlotsInUse`, `gatewaySlotsConfigured` (dynamic capacity; may change at runtime)
- `inferenceActive`, `inferenceQueued`
- `activeModels[]` (name + residency), with Whisper's GPU residency visible
- optional `vramUsedMiB`, `vramTotalMiB`, `ramUsedMiB`
- optional aggregate `gatewayThroughputTokPerSec`
Per-request model performance (model, warm/cold, tok/s, durations, bottleneck) already arrives via the
legacy `OLLAMA_METRICS` event and needs no change.

See `WINDOWS_ACTIVITY_AUDIT.md` §2.2/§6 and `OVERLAY_LIVE_ACTIVITY_DESIGN.md` §1.2 for how these map
to the overlay and diagnostics panel.

🤖 Generated with [Claude Code](https://claude.com/claude-code)

## Confirmed extension — owner measurements, 2026-11-25

| Runtime configuration | Aggregate throughput | Placement / observation |
|---|---:|---|
| 4 slots × 8192 | 147.78 tok/s | Owner-confirmed throughput |
| 4 slots × 16384 | 148.67 tok/s | 100% GPU |
| 4 slots × 32768 | 85.89 tok/s | Partial CPU offload |
| 4 slots × 24576, flash attention, q8_0 KV | 146.15 tok/s | 100% GPU; total GPU including voice 14711 / 16380 MiB |

The owner recommends `OLLAMA_NUM_PARALLEL=4`, `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`, with a **stable physical num_ctx=24576**. These are service settings for an operator-controlled rollout; Core does not change them automatically. Reported remaining GPU memory is 1669 MiB at the sampled point, not a reservation or a guaranteed future minimum.

Earlier mixed test with three background requests: interactive TTFT 257 ms, first visible content 639 ms, interactive generation 35.87 tok/s, no runner reloads. Mixing physical context sizes caused reloads and interactive TTFT 89.50 s. This is why logical profile budgets must never become per-agent physical num_ctx values.

### Long-prompt interaction at the recommended setting

| Condition | Interactive TTFT | First visible content |
|---|---:|---:|
| Idle | 159 ms | 342 ms |
| Concurrent long-prompt processing | 1116 ms | 3367 ms |
| Background token generation | 279–288 ms | 762–811 ms |

Three background agents and an interactive conversation ran simultaneously with long prompts. Nevertheless, prompt processing materially increased interactive latency. **A reserved gateway slot does not reserve GPU compute, memory bandwidth or prefill capacity. Gateway priority cannot preempt work already executing in Ollama.** The data establish concurrency, not a universal interactive latency guarantee.

Implementation implications: deterministic acknowledgments, interactive admission priority, logical background budgets (title 2048, memory 4096, research/planning 8192), stable 24K physical context, bounded/selective history and tool schemas, and configurable spacing between background admissions. The initial spacing value of 250 ms is a tunable mitigation, **not a benchmark-validated optimal value**. It staggers submission starts, does not wait for prompt evaluation or generation to complete, and does not serialize background generation. Future measurements should compare 0/100/250/500 ms with identical prompt lengths and arrival times, tracking p50/p95 conversational TTFT and first content as well as throughput.

Phase timing telemetry must use Ollama final-response `prompt_eval_duration` and `eval_duration` when supplied. Before the first streamed output, Core cannot reliably distinguish scheduler queue, model load, prefill and transport; it must not present that entire interval as measured prompt processing. `phaseTimingsMeasured=false` or absent timing fields means unavailable, not zero work. See `PHASE2_IMPLEMENTATION.md` for the shipped contract and validation status.
