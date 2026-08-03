# Jarvis

Jarvis is a long-term AI operating system backend foundation.

Version `0.2` provides a headless Spring Boot backend for Ubuntu Server 24.04 LTS, Windows, Java 21, Maven, and provider-independent AI chat through Ollama.

## Modules

- `jarvis-common` - shared DTOs, constants, and cross-module types.
- `jarvis-api` - REST controllers and WebSocket endpoint configuration.
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

The chat flow is `ChatController -> ChatService -> ConversationService -> PromptBuilder -> AIProvider -> OllamaProvider -> Ollama HTTP API`.

## Configuration

Ollama defaults to `http://localhost:11434` and model `qwen3:14b`.

Override with:

```yaml
jarvis:
  ai:
    provider: ollama
    model: qwen3:14b
    base-url: http://localhost:11434
    identity-file: file:config/jarvis.md
```

The AI identity is loaded from `config/jarvis.md`.
