# Phase 2 controlled release — Core contract 1.5.0

The user confirmed final Windows compatibility with Core 1.5.0 on 2026-11-25.
This release is prepared for an operator-controlled Ubuntu deployment. No Ubuntu
deployment, service restart or production credential/database change was performed.

## Production configuration

Merge [the exact Phase 2 overlay](../../config/examples/application-companion-phase2.yml)
into the deployment's existing application.yml. Keep one `jarvis:` mapping and
preserve all deployment-specific account, network, storage and voice settings.
Replace the obsolete 262144/30000 context settings with 24576/2048.

Required admission tuple: `jarvis.model-gateway.concurrency: 4`,
`reserved-interactive: 1`, `background-submit-spacing-millis: 250`,
`jarvis.companion.task-concurrency: 3`,
`jarvis.inference.physical-context-window: 24576`.
Autonomous Memory and Dreaming remain explicitly disabled.

The separately configured, owner-benchmarked **Ollama service environment** is:

```text
OLLAMA_NUM_PARALLEL=4
OLLAMA_FLASH_ATTENTION=1
OLLAMA_KV_CACHE_TYPE=q8_0
```

Core sends a stable physical num_ctx=24576 across logical profiles. The overlay
does not edit Ollama's environment. An admission reservation and spacing do not
preempt GPU work already running inside Ollama. The 250 ms interval is configurable;
its optimal value remains an operator benchmark decision.

## Included release materials

- Java implementation, tests and packaged application defaults across the existing modules.
- Contract [1.5.0](API_CONTRACT.md), version marker and [schemas/examples](schemas/1.5.0).
- [Implementation, limits and rollout](phase2/PHASE2_IMPLEMENTATION.md),
  [measured hardware results](phase2/OLLAMA_CONCURRENCY_BENCHMARKS.md),
  [Windows coordination](phase2/PHASE2_CORE_COORDINATION.md), and the original audits/designs.
- [Benchmark](../../scripts/companion/benchmark_inference.py),
  [its tests](../../scripts/companion/test_benchmark_inference.py), and
  [packaged Core smoke verifier](../../scripts/companion/verify_packaged_core.py).
- [Recorded implementation validation](phase2/PHASE2_TEST_RESULTS.json): 977 Java
  tests, 5 Python tests, and isolated production-JAR startup/chat/delegation checks.

From the repository root, build with Java 21 using `mvn package`. Run the Python
tests with `python -m unittest discover -s scripts/companion -p test_benchmark_inference.py -v`.
The smoke verifier takes explicit `--java`, `--jar` and a NEW `--out` directory;
it uses loopback fake Ollama and isolated SQLite, never the production service.

## Commit hygiene

Stage source, configuration examples, tests, contracts and curated reports only.
Build outputs/JARs, logs, runtime databases, smoke directories, account secrets and
production configuration/credentials are not release source files. The example
contains no credentials. Test passwords/tokens in synthetic fixtures are not
production credentials. Commit verification records the exact staged manifest and
checks for binary/runtime artifacts and recognizable secret patterns without
printing credential values.

## Final review checks

Reviewed all 83 release candidate files and the implementation diff. No build/runtime binaries, local databases, production credentials or recognizable secret patterns were found in this candidate set. Synthetic authentication fixtures are explicitly test-only. The production overlay and packaged defaults both parse without duplicate YAML keys and match 4/1/3, 24576 context, 250 ms spacing, and disabled Autonomous Memory/Dreaming. All 1.5.0 JSON files parse and local schema references resolve. Five tests pass from the repository-local benchmark tooling. The unchanged Java implementation retains the recorded 977-test full regression and isolated JAR verification.

## Production delegation integration fix

See [the integration fix report](phase2/PRODUCTION_DELEGATION_FIX.md) for the
three-task DELEGATED correction, production-path regression evidence and manual
verification. Existing contract 1.5.0 and benchmarked configuration remain valid.
