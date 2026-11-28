# Inference 1.9.0 — status 2026-11-27

Local implementation and production JAR verified. Full Maven reactor including companion-integration: 1086 tests, 0 failures/errors, 3 opt-in skips. Separate real local Ollama GPT-OSS 20B checks: text streaming, JSON and native tool calling passed.

No production deployment or llama.cpp installation occurred. Production SSH authentication failed. CUDA/GPU execution, model VISION qualification and the full benchmark matrix remain unverified. Partial implementation limits are explicit in API_CONTRACT.md, DEPLOYMENT.md and SHARED/INFERENCE_IMPLEMENTATION_REPORT.md. Windows sources were not edited.
