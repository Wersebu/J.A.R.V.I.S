# Model Context Protocol

J.A.R.V.I.S. Core supports Model Context Protocol (MCP) through the existing native tool architecture. MCP tools are discovered dynamically, wrapped as `JarvisTool` instances, and exposed to the same `ToolManager`, `ToolRegistry`, native schema mapper, tool policy, diagnostics, and logging path as built-in tools.

## Architecture

```text
LLM
  -> Core ToolRegistry
  -> McpDynamicToolSource
  -> McpServerManager
  -> MCP transport
  -> MCP server
  -> external application
```

For a remote desktop application such as Roblox Studio, Core must not launch the MCP process on Ubuntu. Configure that server as `execution-host: WINDOWS` and route it through the Windows bridge when that bridge is connected.

```text
LLM
  -> Core
  -> Windows client / bridge
  -> stdio MCP server
  -> Roblox Studio
  -> Windows client / bridge
  -> Core
  -> LLM
```

## Configuration

MCP is disabled by default. With `jarvis.mcp.enabled=false`, J.A.R.V.I.S. behaves exactly as it did before MCP support was added.

```yaml
jarvis:
  mcp:
    enabled: true
    servers:
      local-example:
        enabled: true
        execution-host: CORE
        transport: STDIO
        command: node
        args:
          - ./server.js
        access-level: READ
        startup-timeout: 10s
        initialize-timeout: 10s
        list-tools-timeout: 5s
        call-timeout: 30s
```

Supported execution hosts:

| Host | Meaning |
|---|---|
| `CORE` | Core starts and talks to the MCP server locally. Use this only for MCP servers installed on the same machine as Core. |
| `WINDOWS` | The MCP server must run on the Windows client machine. Core marks it as bridge-required until the Windows bridge is available. |

Supported transports:

| Transport | Meaning |
|---|---|
| `STDIO` | JSON-RPC over stdin/stdout. |
| `WINDOWS_BRIDGE` | Placeholder transport for MCP servers launched by the Windows client and proxied back to Core. |

Access levels:

| Level | Intended capability |
|---|---|
| `READ` | Inspect/search/read operations. |
| `EDIT` | Project/content mutation. |
| `TEST` | Test or playtest operations. |
| `AUTONOMOUS` | Future edit-test-fix loops. |

## Tool Discovery

Discovery follows the MCP protocol:

```text
initialize
  -> tools/list
  -> ToolRegistry dynamic registration
```

Discovered tools are namespaced as:

```text
mcp_<server>_<tool>
```

For example, an MCP server id `roblox` exposing a tool named `script_read` becomes:

```text
mcp_roblox_script_read
```

This prevents collisions with native tools and with tools from other MCP servers.

## API

```bash
curl http://localhost:8080/api/v1/mcp/status
curl -X POST http://localhost:8080/api/v1/mcp/local-example/connect
curl -X POST http://localhost:8080/api/v1/mcp/local-example/disconnect
curl http://localhost:8080/api/v1/tools
```

`/api/v1/tools` includes MCP tools only when MCP is enabled and discovery succeeds.

## Roblox Studio MCP

Roblox Studio runs on Windows, so configure it as a Windows-hosted MCP server:

```yaml
jarvis:
  mcp:
    enabled: true
    servers:
      roblox:
        enabled: true
        execution-host: WINDOWS
        transport: WINDOWS_BRIDGE
        command: cmd.exe
        args:
          - /c
          - "%LOCALAPPDATA%\\Roblox\\mcp.bat"
        access-level: EDIT
```

Core will not run `cmd.exe` on Ubuntu. The Windows bridge is responsible for launching the local process and relaying MCP messages.

Manual smoke test once the Windows bridge is available:

1. Start Roblox Studio.
2. Ensure `%LOCALAPPDATA%\Roblox\mcp.bat` exists and can be run from Windows.
3. Enable `jarvis.mcp.enabled=true` and the `roblox` server.
4. Start Core.
5. Open Windows UI and connect the bridge.
6. Verify `GET /api/v1/mcp/status` reports `CONNECTED`.
7. Verify `GET /api/v1/tools` includes `mcp_roblox_*` tools.
8. Execute a safe read-only Roblox MCP operation first.

## Structured And Binary Content

MCP responses preserve:

- text content,
- structured content,
- MIME type,
- binary/base64 payload fields.

Core does not collapse MCP responses into a blind `toString()`. Large binary payloads should not be logged; future vision-capable processing can route supported image content into the existing attachment/vision pipeline.

## Troubleshooting

| Symptom | Likely cause | Action |
|---|---|---|
| `MCP server is disabled` | Server config has `enabled: false` or global MCP is disabled | Enable both `jarvis.mcp.enabled` and the server entry |
| `MCP command is required` | `command` is empty for a `CORE` + `STDIO` server | Set the command and args |
| `requires WINDOWS / WINDOWS_BRIDGE bridge` | Server must be launched on Windows | Start or implement the Windows MCP bridge |
| `initialize timeout` | MCP process started but did not answer | Check the MCP server logs and increase `initialize-timeout` if needed |
| `tools/list timeout` | Discovery is slow or blocked | Check the MCP server and transport |
| `MCP request timed out` | `tools/call` exceeded `call-timeout` | Increase timeout or investigate the external app |
| Roblox unavailable | Roblox Studio is closed or MCP script is missing | Start Roblox Studio and verify `mcp.bat` |

## Logs

MCP logs use the `[MCP]` prefix:

```text
[MCP] connecting: roblox
[MCP] connected: roblox
[MCP] discovered 24 tools
[MCP] call: roblox.script_read
[MCP] completed: 183 ms
[MCP] disconnected
```

Secrets and large binary payloads are intentionally not logged.
