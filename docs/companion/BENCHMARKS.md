# Linux inference benchmark

Prepared, not run on production hardware. No production result is claimed.

Use scripts/benchmark-companion-inference.py with Python 3 standard library and nvidia-smi on the existing Linux RTX 4060 Ti 16 GB host. It sends inference load only with an explicit --run flag, never changes Ollama environment/service settings, never restarts services, and does not install models.

In an approved load-test window:

    python3 scripts/benchmark-companion-inference.py --run --url http://127.0.0.1:11434 --model gpt-oss:20b --context 4096 --repeats 3 --output companion-benchmark.json

It runs groups of 1, 2 and 3 requests. In groups of 2/3, backgrounds start first and the interactive-labelled request starts 250ms later. Reports include per-request first generated token (including thinking), first visible token, total interactive latency, model load time, eval_count/eval_duration tokens per second, aggregate generated tokens / group wall time, and sampled used/total MiB per GPU UUID every 200ms. Missing nvidia-smi produces an empty sample list, not invented values. Samples include other GPU processes. Warm/cold load times are recorded; compare steady-state repeats separately.

The harness measures raw Ollama contention, independently of gateway reservations. To measure gateway end-to-end admission and conversation latency, run the same workload through the backend after configuring its limit to 1/2/3 in a separate nonproduction instance; do not infer that result from this raw-provider harness. Actual server overlap must be verified from latency/throughput and server telemetry: three simultaneous client requests may still be serialized by Ollama. No daemon parallelism setting has been changed. Context and model names are CLI arguments, not hardware assumptions.

Acceptance worksheet: record host/software versions, current daemon parallelism, context size, cold vs warm runs, each TTFT/TPS/latency, aggregate TPS, peak MiB from raw samples, failures/OOM and whether interactive reservation was enabled. Do not approve concurrency 2/3 merely because requests completed; compare foreground latency and remaining VRAM headroom.
