# Jarvis

Jarvis is a long-term AI operating system backend foundation.

Version `0.6` provides a headless Spring Boot backend for Ubuntu Server 24.04 LTS, Windows, Java 21, Maven, provider-independent AI chat through Ollama, brain routing, real-time SSE token streaming, a metadata-only knowledge engine foundation, keyword retrieval over indexed metadata, and structured context building.

## Modules

- `jarvis-common` - shared DTOs, constants, and cross-module types.
- `jarvis-brain-router` - logical brain catalog and deterministic brain routing.
- `jarvis-api` - REST controllers and WebSocket endpoint configuration.
- `jarvis-knowledge` - metadata-only knowledge document index and filesystem watcher.
- `jarvis-core` - Spring Boot application and orchestration services.
- `jarvis-memory` - in-memory conversation history with replaceable interfaces.
- `jarvis-ollama` - Ollama implementation hidden behind the provider-independent AI contract.
- `jarvis-planner` - planning contracts for future planning engines.
- `jarvis-tools` - tool execution framework contracts.
- `jarvis-plugin-sdk` - plugin extension contracts for future external JAR plugins.

## Run

```bash
mvn clean spring-boot:run -pl jarvis-core -am
```

## Health

```bash
curl http://localhost:8080/api/health
```

Expected response:

```json
{"status":"online","version":"0.1"}
```

## Chat v0.2

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"default","message":"Hello"}'
```

The chat flow is `ChatController -> ChatService -> ConversationService -> PromptBuilder -> BrainRouter -> Brain -> AIProvider -> OllamaProvider -> Ollama HTTP API`.

## Streaming Chat v0.4

```bash
curl -N "http://localhost:8080/api/v1/chat/stream?conversationId=default&message=Hello"
```

The streaming endpoint emits typed Server-Sent Events such as `REQUEST_RECEIVED`, `BRAIN_ROUTING`, `PROMPT_BUILDING`, `MODEL_LOADING`, `THINKING`, `GENERATING`, `TOKEN`, `FINISHED`, `IDLE`, and `ERROR`.

## Knowledge v0.5

```bash
curl http://localhost:8080/api/v1/knowledge
curl -X POST http://localhost:8080/api/v1/knowledge/reindex
```

The knowledge engine keeps metadata only. Source documents remain in the configured knowledge root and are not moved or modified.

## Retrieval v0.5.1

```bash
curl -X POST http://localhost:8080/api/v1/knowledge/retrieve \
  -H "Content-Type: application/json" \
  -d '{"query":"Spring Dependency Injection"}'
```

Retrieval depends on the `KnowledgeRetriever` interface. The default implementation uses keyword scoring over indexed metadata only and does not read source files from disk.

## Context Builder v0.6

```bash
curl -X POST http://localhost:8080/api/v1/context/build \
  -H "Content-Type: application/json" \
  -d '{"query":"Spring Dependency Injection"}'
```

The context builder retrieves matching metadata, loads supported source files, and returns a `KnowledgeContext` without invoking Ollama.

## Configuration

Ollama defaults to `http://localhost:11434`. Models are selected through configured logical brains.

Override with:

```yaml
jarvis:
  ai:
    base-url: http://localhost:11434
    identity-file: file:config/jarvis.md

brains:
  FAST:
    provider: ollama
    model: qwen3:8b
  REASONING:
    provider: ollama
    model: qwen3:14b
  CLASSIFIER:
    provider: ollama
    model: qwen3:0.6b
```

The AI identity is loaded from `config/jarvis.md`.

Knowledge defaults:

```yaml
knowledge:
  root: ./knowledge
  watch: true
  preview-length: 500
```
