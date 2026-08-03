# Jarvis

Jarvis is a long-term AI operating system backend foundation.

Version `0.1` provides a headless Spring Boot backend for Ubuntu Server 24.04 LTS, Java 21, and Maven.

## Modules

- `jarvis-common` - shared DTOs, constants, and cross-module types.
- `jarvis-api` - REST controllers and WebSocket endpoint configuration.
- `jarvis-core` - Spring Boot application and orchestration services.
- `jarvis-memory` - in-memory conversation history with replaceable interfaces.
- `jarvis-ollama` - dedicated Ollama HTTP API client.
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

## Chat

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"conversationId":"default","message":"Hello"}'
```

The chat flow is `ChatService -> OllamaService -> plain text response`.

## Configuration

Ollama defaults to `http://localhost:11434` and model `llama3.1`.

Override with:

```properties
jarvis.ollama.base-url=http://localhost:11434
jarvis.ollama.model=llama3.1
```
