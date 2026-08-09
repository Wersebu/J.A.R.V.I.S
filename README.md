# Jarvis

Jarvis is a long-term AI operating system backend foundation.

Version `2.6.0` provides a headless Spring Boot backend for Ubuntu Server 24.04 LTS, Windows, Java 21, Maven, provider-independent AI chat through Ollama, brain routing, real-time SSE/WebSocket streaming, Knowledge Workspace, Cognitive Memory, native tool calling, SearXNG-backed Web Search, temporary chat attachments, and a unified Cognitive Event Bus.

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
{"status":"online","version":"2.6.0"}
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

Temporary workspace defaults:

```yaml
jarvis:
  workspace:
    root: ./temp-workspaces
    ttl: 60m
    cleanup-interval: 5m
    max-file-size-bytes: 2097152
    max-workspace-size-bytes: 20971520
    max-total-size-bytes: 209715200
    max-files-per-workspace: 20
    minimum-free-disk-space-bytes: 536870912
    prompt-max-characters: 24000
```

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
  -> ANSWER_SOURCES event with trusted source metadata
  -> User
```

The native tool is named `web` and exposes:

```json
{"action":"TOOL_CALL","tool":"web","operation":"SEARCH_WEB","arguments":{"query":"RTX 4060 Ti 16GB cena Polska","maxResults":5},"reason":"The user asked for current internet information."}
```

When a search result snippet does not contain enough evidence, the model may open a result page:

```json
{"action":"TOOL_CALL","tool":"web","operation":"READ_WEB_PAGE","arguments":{"url":"https://example.com/result"},"reason":"Search result snippets did not contain the needed price/details."}
```

The model receives normalized search results only: title, URL, short snippet, and source. Core does not pass raw SearXNG JSON, HTML, ads, or full pages into the context window.

Web search is iterative. After every `web.SEARCH_WEB` call, Core evaluates source quality before allowing the tool loop to finish. If results are weak, irrelevant, or from the wrong domain, the tool observation is marked with `sourceQualityAccepted=false`; the model receives that observation and may change the query, search a specific site, or try again within the configured tool-call budget. Only accepted results are exposed as answer sources.

`READ_WEB_PAGE` fetches only public `http`/`https` URLs, blocks private/local addresses, strips scripts/styles/HTML, normalizes visible text, and truncates it before passing it back as a tool observation.

Answer sources are attached by Core, not by the LLM. After a successful `web.SEARCH_WEB` call, Core extracts trusted source metadata from the executed `ToolResult`, validates that each URL uses only `http` or `https`, removes duplicate URLs and duplicate display domains, and emits an `ANSWER_SOURCES` cognitive event. The default UI limit is five sources. If SearXNG returns no usable results, no sources section is shown.

Windows renders these sources as compact clickable chips under the assistant response. The client never displays raw long URLs in the chat body, and it performs its own second URL safety check before opening a source in the default browser. Unsafe schemes such as `file:`, `javascript:`, `cmd:`, or `powershell:` are ignored.

Configuration:

```yaml
jarvis:
  web-search:
    enabled: true
    base-url: http://127.0.0.1:8888
    default-max-results: 5
    hard-max-results: 10
    snippet-max-length: 320
    page-max-length: 8000
    connect-timeout: 2s
    read-timeout: 8s
```

The default SearXNG port is `8888` because J.A.R.V.I.S. Core uses `8080` for its own API. If Core is moved to another port, `jarvis.web-search.base-url` and the compose port mapping can be changed to `http://127.0.0.1:8080`.

Start SearXNG on Ubuntu:

```bash
cd /opt/jarvis/deploy/searxng
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

## File Workspace & Attachments v2.6

Temporary attachments are not Knowledge. They are uploaded into isolated workspaces under `temp-workspaces/<workspace-id>/input`, passed to the prompt as bounded data context, and removed by TTL cleanup unless a future explicit export/persistent-save flow copies them elsewhere.

Core exposes:

```bash
curl -X POST "http://localhost:8080/api/v1/workspaces?conversationId=default"
curl -X POST "http://localhost:8080/api/v1/workspaces/<workspace-id>/attachments" \
  -F "files=@Example.java" \
  -F "files=@application.yml"
curl http://localhost:8080/api/v1/workspaces/<workspace-id>
curl -X DELETE http://localhost:8080/api/v1/workspaces/<workspace-id>
```

Chat requests may include attachment references:

```json
{
  "conversationId": "default",
  "message": "Co robi ten plik?",
  "attachments": [
    {"workspaceId": "<workspace-id>", "attachmentId": "<attachment-id>"}
  ]
}
```

Supported v2.6 text/code formats are `.txt`, `.md`, `.log`, `.csv`, `.java`, `.py`, `.js`, `.ts`, `.html`, `.css`, `.json`, `.xml`, `.yml`, `.yaml`, `.properties`, `.sql`, `.sh`, and `.ps1`. Unsupported binary files are rejected before prompt construction.

Security rules:

- Core never exposes filesystem paths to the model.
- User file names are metadata only; internal stored names use safe IDs.
- Path traversal, absolute paths, and duplicate names cannot overwrite files outside the workspace.
- Upload writes are atomic and incomplete `.partial` files are not registered as attachments.
- Cleanup runs on startup and periodically; TTL is based on `lastAccessAt`, refreshed only by real workspace operations.
- Workspace storage limits and minimum free disk thresholds are configurable.

Prompt injection format:

```text
=== TEMPORARY ATTACHMENTS ===

ATTACHMENT
Name: Example.java
Type: java

<content>

END ATTACHMENT

=== END TEMPORARY ATTACHMENTS ===
```

Attachment content is treated strictly as user data. Text inside uploaded files is never treated as system instructions.

The Windows client supports multi-select file picker, drag-and-drop, pre-send attachment chips, removing individual files before send, upload status through normal request state, and attachment metadata shown in chat history.

Future archive/document work should use the same temporary workspace boundaries:

```text
input/archive.zip -> extracted/project/ -> output/project-fixed.zip
```

Archive extraction must later protect against Zip Slip, absolute paths, symbolic links, decompression bombs, excessive file counts, and writing outside `workspace/extracted/`. PDF, DOCX, XLSX, PPTX, OCR, Vision, and archive parsers are intentionally not implemented in v2.6.
