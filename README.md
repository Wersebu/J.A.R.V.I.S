# Jarvis

Jarvis is a long-term AI operating system backend foundation.

Version `2.5.0` provides a headless Spring Boot backend for Ubuntu Server 24.04 LTS, Windows, Java 21, Maven, provider-independent AI chat through Ollama, brain routing, real-time SSE/WebSocket streaming, Knowledge Workspace, Cognitive Memory, native tool calling, SearXNG-backed Web Search, and a unified Cognitive Event Bus.

## Modules

- `jarvis-common` - shared DTOs, constants, and cross-module types.
- `jarvis-brain-router` - logical brain catalog and deterministic brain routing.
- `jarvis-api` - REST controllers and WebSocket endpoint configuration.
- `jarvis-knowledge` - metadata-only knowledge document index and filesystem watcher.
- `jarvis-core` - Spring Boot application and orchestration services.
- `jarvis-memory` - in-memory conversation history with replaceable interfaces.
- `jarvis-ollama` - Ollama implementation hidden behind the provider-independent AI contract.
- `jarvis-planner` - planning contracts for future planning engines.
- `jarvis-tools` - native tool execution framework, KnowledgeTool, and WebSearchTool.
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
{"status":"online","version":"2.5.0"}
```

## Chat v0.2

```bash
curl -X POST http://localhost:8080/api/v1/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"default","message":"Hello"}'
```

The chat flow is `ChatController -> ChatService -> ConversationService -> BrainRouter -> KnowledgeRetriever -> ContextBuilder -> PromptBuilder -> AIProvider -> OllamaProvider -> Ollama HTTP API`.

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

## Prompt Debug v0.7

```bash
curl -X POST http://localhost:8080/api/v1/prompt/debug \
  -H "Content-Type: application/json" \
  -d '{"query":"Spring Dependency Injection"}'
```

The debug endpoint shows the system prompt, injected knowledge, user prompt, and final prompt without invoking Ollama.

## Cognitive Events v0.8

```bash
curl http://localhost:8080/api/v1/events/schema
curl http://localhost:8080/api/v1/events/sample
```

The streaming chat endpoint emits unified `CognitiveEvent` payloads for request, routing, retrieval, context, prompt, model, token, and completion steps. Each event includes `requestId`, `conversationId`, `timestamp`, and optional `nodeId`.

## Configuration

Ollama defaults to `http://localhost:11434`. J.A.R.V.I.S. uses `gpt-oss:20b` as the default model and routes requests by reasoning level: `LOW`, `MEDIUM`, or `HIGH`.

Override with:

```yaml
jarvis:
  ai:
    base-url: http://localhost:11434
    identity-file: file:config/jarvis.md

brains:
  FAST:
    provider: ollama
    model: gpt-oss:20b
  REASONING:
    provider: ollama
    model: gpt-oss:20b
  CLASSIFIER:
    provider: ollama
    model: gpt-oss:20b
```

The AI identity is loaded from `config/jarvis.md`.

Knowledge defaults:

```yaml
knowledge:
  root: ./knowledge
  watch: true
  preview-length: 500
```

## Web Search v2.5

J.A.R.V.I.S. can search current web information through a local self-hosted SearXNG instance. The model does not call SearXNG directly. The flow is:

```text
User
  -> LLM MAIN_MODEL_ACTION TOOL_REQUEST
  -> Core ToolCallingRuntime
  -> WebSearchTool
  -> local SearXNG JSON API
  -> ToolResult
  -> LLM final answer
  -> User
```

The native tool is named `web` and exposes:

```json
{"action":"TOOL_CALL","tool":"web","operation":"SEARCH_WEB","arguments":{"query":"RTX 4060 Ti 16GB cena Polska","maxResults":5},"reason":"The user asked for current internet information."}
```

The model receives normalized search results only: title, URL, short snippet, and source. Core does not pass raw SearXNG JSON, HTML, ads, or full pages into the context window.

Configuration:

```yaml
jarvis:
  web-search:
    enabled: true
    base-url: http://127.0.0.1:8888
    default-max-results: 5
    hard-max-results: 10
    snippet-max-length: 320
    connect-timeout: 2s
    read-timeout: 8s
```

The default SearXNG port is `8888` because J.A.R.V.I.S. Core uses `8080` for its own API. If Core is moved to another port, `jarvis.web-search.base-url` and the compose port mapping can be changed to `http://127.0.0.1:8080`.

Start SearXNG on Ubuntu:

```bash
cd /opt/jarvis/SERVER/deploy/searxng
docker compose up -d
```

Stop or restart:

```bash
docker compose down
docker compose restart
```

Check status:

```bash
docker compose ps
curl "http://127.0.0.1:8888/search?q=jarvis&format=json"
```

Manual test search:

```bash
curl "http://127.0.0.1:8888/search?q=RTX%204060%20Ti%2016GB%20cena%20Polska&format=json"
```

Diagnostics:

```text
[WEB_SEARCH] query="RTX 4060 Ti 16GB cena Polska"
[WEB_SEARCH] results=5
[WEB_SEARCH] duration=XXXms
```

If SearXNG is unavailable, `WebSearchTool` returns a failed `ToolResult` with `errorCode=WEB_SEARCH_FAILED`. It never invents web results and never allows the model to change the configured SearXNG base URL.
