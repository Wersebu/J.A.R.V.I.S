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
| `WINDOWS_BRIDGE` | Real bridge transport over the existing Core <-> Windows WebSocket. Core sends MCP JSON-RPC work to Windows, and Windows launches the local stdio MCP server. |

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

## Discovery Lifecycle & Reliability

A real Roblox Studio connection can go `connecting -> connected` and still end up with 0 discovered
tools if `tools/list` fails or comes back empty right after - `CONNECTED` on its own only ever meant
"the MCP handshake succeeded", never "tools were actually discovered" or "the external application
is attached". The lifecycle is now enforced end to end, with each phase logged distinctly instead of
collapsing into one `CONNECTED` state:

```text
BRIDGE_CONNECTED (Windows WebSocket registered)
  -> PROCESS_STARTING / PROCESS_STARTED (mcp.bat launched, real PID logged)
  -> INITIALIZING -> MCP_INITIALIZED (JSON-RPC initialize + notifications/initialized done)
  -> DISCOVERING -> TOOLS_LIST_RECEIVED (real tool count/names logged)
  -> tools registered in DefaultToolRegistry / ToolManager
```

Three reliability fixes close the gaps found while diagnosing a real "connects fine, 0 tools" run:

- **No orphaned MCP process on reconnect.** `WindowsMcpProcessClient#close()` used to call only
  `Process#destroy()`, which terminates just the immediate child - a batch-file wrapper (`mcp.bat`)
  spawning a real interpreter process underneath it left that grandchild running orphaned, still
  holding whatever local resource (port, named pipe, the Roblox Studio plugin connection) the next
  launched process then failed to acquire. `close()` now walks `Process#toHandle().descendants()`
  and destroys the whole tree, waits a short grace period, and force-destroys anything still alive.
  `DefaultMcpServerManager` (Core side) also now closes the previous `McpClient` - sending Windows a
  real `MCP_DISCONNECT` - before a reactivation replaces it, instead of just dropping the Java
  reference and leaving Windows unaware the old process should be torn down.
- **A silently-dead process is never reused.** `WindowsMcpProcessClient` used to trust its own
  in-memory `connected` flag alone; a process that crashed on its own (e.g. Roblox Studio was closed)
  left `connected=true` unchanged, so the next call reused the dead client and failed instead of
  reconnecting. `initialize()`/`ensureConnected()` now also check `Process#isAlive()` and
  transparently relaunch when the flag and reality disagree.
- **A genuinely empty discovery is retried, bounded, not cached forever.** `DefaultMcpServerManager`
  used to treat any `CONNECTED` server with an empty `tools/list` result as final and never retried -
  the exact failure mode when Roblox Studio (or "Assistant -> Manage MCP Servers -> Enable Studio as
  MCP server") had not attached yet at the moment of the first `tools/list` call. It now retries up
  to 5 times with a short backoff between attempts, then stops automatically with a clear
  `lastError` explaining what to check - and a manual reconnect (`POST /api/v1/mcp/{serverId}/connect`,
  or a fresh Windows bridge registration) always clears that bound for a genuinely fresh attempt.

A missing or wrong-shaped `payload.tools` field in a `MCP_BRIDGE_RESPONSE` is also no longer treated
as a valid empty discovery - `WebSocketWindowsMcpBridgeGateway#listTools` now rejects it outright as a
protocol error (`TOOLS_FIELD_NOT_ARRAY`), so a real Windows-side bug can never masquerade as "Roblox
Studio just isn't attached yet".

## Real Tool Calls Timing Out Despite Fast Discovery

Discovery (`MCP_CONNECT` + `MCP_LIST_TOOLS`) can succeed perfectly - fast, `discoveredTools > 0`,
tools registered - and a real `MCP_CALL_TOOL` (e.g. `list_roblox_studios`) can still appear to time
out from Core's point of view. This turned out to be a **separate, more fundamental bug**, found by
comparing a real production trace on both sides of the bridge:

```text
Windows side (real trace): request received -> tools/call sent -> response received -> durationMs=0
Core side (same trace):    request sent -> ... -> timed out after 65s -> "stale response" 2-4 minutes later
```

The Windows bridge answered in **0-4 milliseconds**. Core did not see that response for **2-4
minutes** - and the delay lined up exactly with how long the *chat pipeline that triggered the call*
took to finish, not with anything MCP-specific.

**Root cause:** chat and the MCP bridge share one persistent WebSocket session (`/ws/jarvis`).
Jakarta/Spring WebSocket containers deliver one session's incoming frames strictly sequentially -
they will never invoke the handler again for that session until the current invocation returns.
`JarvisWebSocketHandler.handleTextMessage` used to run `chatService.stream(...)` **synchronously**
for a chat request, so a single long-running native tool loop (easily minutes) blocked delivery of
*every other frame on that same session* for its entire duration - including the `MCP_BRIDGE_RESPONSE`
that very loop was waiting on. The response was sent by Windows almost instantly, but sat queued,
undelivered, until the chat pipeline itself finally finished and freed the session for the next frame
- a self-inflicted deadlock-shaped bottleneck, not a networking or Roblox-side issue.

**Fix:** `handleTextMessage` now dispatches chat processing onto a dedicated executor
(`chatExecutor` in `JarvisWebSocketHandler`) and returns immediately, regardless of how long the
underlying pipeline takes. The container is then free to keep delivering other frames - MCP bridge
responses in particular - on the same session throughout. Bridge messages (`MCP_BRIDGE_REGISTER`,
`MCP_BRIDGE_RESPONSE`) were already handled synchronously and fast, so they are unaffected other than
now actually being reachable while a chat request is in flight.

This explains why a slow-looking real tool call can be entirely explained by "how long was the
overall chat turn taking" rather than by the external application itself.

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
          - "cd /d %LOCALAPPDATA%\\Roblox && .\\mcp.bat"
        access-level: EDIT
```

Core will not run `cmd.exe` on Ubuntu. The Windows bridge registers through `/ws/jarvis`, launches the local process, performs the MCP handshake, relays `tools/list` and `tools/call`, and returns structured MCP content to Core.

When the Windows client sends `MCP_BRIDGE_REGISTER`, Core starts MCP activation asynchronously for every enabled server configured as `execution-host: WINDOWS` and `transport: WINDOWS_BRIDGE`. The WebSocket handler is not blocked. Each matching server is initialized through the Windows bridge, `tools/list` is called immediately, and discovered tools become available through the normal `ToolManager` and `/api/v1/tools` path. Core then emits `MCP_STATUS_CHANGED` so the Windows UI can refresh the MCP panel without a restart.

Large MCP discovery responses, especially `tools/list` responses with long descriptions and JSON schemas, travel over the shared `/ws/jarvis` WebSocket as text. Core configures the embedded WebSocket container through:

```yaml
jarvis:
  websocket:
    max-text-message-size: 4194304
    max-binary-message-size: 8388608
```

The Windows bridge logs the UTF-8 byte size and whether OkHttp accepted each outbound bridge message into its send queue. These limits are intended for large MCP metadata responses, not for future screenshots, image/base64 payloads, or other heavy binary content. Those should use chunking, binary frames, or the attachment pipeline instead of a huge text frame.

Manual smoke test once the Windows bridge is available:

1. Start Roblox Studio.
2. Ensure `%LOCALAPPDATA%\Roblox\mcp.bat` exists and can be run from Windows.
3. Enable `jarvis.mcp.enabled=true` and the `roblox` server.
4. Start Core.
5. Open Windows UI; it registers the bridge over the persistent WebSocket automatically.
6. Verify `GET /api/v1/mcp/status` reports `bridgeConnected=true` and the Roblox server can reach `CONNECTED` after discovery.
7. Verify `GET /api/v1/tools` includes `mcp_roblox_*` tools, and that `discoveredTools` in `/api/v1/mcp/status` is greater than 0 (not just `state=CONNECTED` - see [Discovery Lifecycle & Reliability](#discovery-lifecycle--reliability), `CONNECTED` alone does not guarantee tools were found).
8. Execute a safe read-only Roblox MCP operation first (e.g. `list_roblox_studios`, then `search_game_tree`) - never an editing operation as the first real call.
9. Close and reopen the Windows UI (or otherwise force a bridge reconnect) at least once, then use Task Manager (or `Get-Process java`) to confirm no orphaned `java.exe`/`mcp.bat`-launched process remains from the previous session - the reconnect fix in [Discovery Lifecycle & Reliability](#discovery-lifecycle--reliability) is specifically about this.

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
| `Windows MCP bridge is not connected` | Windows UI is closed or not connected to Core | Start Windows UI and verify the target points at this Core |
| `Failed to start MCP process` | Windows could not launch the configured command | Verify `%LOCALAPPDATA%\Roblox\mcp.bat` exists and runs from `cmd.exe`; the Windows bridge keeps recent sanitized stderr lines for diagnostics |
| `initialize timeout` | MCP process started but did not answer | Check the MCP server logs and increase `initialize-timeout` if needed |
| `tools/list timeout` | Discovery is slow or blocked | Check the MCP server and transport |
| `MCP request timed out` | `tools/call` exceeded `call-timeout` | Increase timeout or investigate the external app |
| Roblox unavailable | Roblox Studio is closed or MCP script is missing | Start Roblox Studio and verify `mcp.bat` |
| First `tools/call` after connect times out, later calls succeed | Roblox Studio (or its companion MCP plugin) was still starting/attaching when the first call was made - the `roblox` server's `call-timeout` is intentionally higher (`60s`) than the generic default for exactly this reason | Wait for Studio to fully load before issuing the first tool call, or retry once Studio is confirmed running |
| `state=CONNECTED` but `discoveredTools=0` right after connecting | `tools/list` legitimately returned 0 tools (e.g. Studio/plugin not attached yet at that exact moment) | This now retries automatically (bounded, with a short backoff) - wait a few seconds, or force it immediately with `POST /api/v1/mcp/roblox/connect`; check `lastError` for the exact attempt count once retries are exhausted |
| `Windows MCP bridge returned a malformed tools/list response ... expected payload.tools to be an array` | The Windows bridge sent a response shaped differently than the documented `{"payload":{"tools":[...]}}` contract (a real protocol bug, not a normal empty discovery) | Check the Windows bridge's own logs for the `MCP_LIST_TOOLS` request/response around that time; this is deliberately never treated the same as a genuine empty tool list |
| Reconnect after a Windows bridge drop still can't discover tools | A previous MCP process was left running (orphaned) and is holding a resource the new one needs (e.g. a local port/pipe the external application only allows one listener on) | Fixed as of this version - `close()` now kills the whole process tree, and Core closes the previous client (sending `MCP_DISCONNECT`) before reactivating; if it still happens, check for orphaned processes manually and file it as a new bug |
| Discovery is fast (`discoveredTools > 0`) but a real `tools/call` still times out, and Windows-side logs show the call actually answered in milliseconds | Was: `handleTextMessage` ran the whole chat pipeline synchronously, blocking delivery of the `MCP_BRIDGE_RESPONSE` on the shared session until the pipeline itself finished - see [Real Tool Calls Timing Out Despite Fast Discovery](#real-tool-calls-timing-out-despite-fast-discovery) | Fixed as of this version - chat processing now runs on a dedicated executor so `handleTextMessage` returns immediately and bridge responses are delivered promptly even during a long chat turn |

Core's own wait for a bridge response is always a few seconds longer than whatever timeout it told the Windows client to use internally (`BRIDGE_RESPONSE_SLACK` in `WebSocketWindowsMcpBridgeGateway`). Without that slack, Core's side of the round trip could time out at (or fractionally before) the Windows client's own watchdog, discarding the pending request; a real, on-time Windows response then had nowhere to go and was logged and dropped as a stale response (`[MCP_BRIDGE] stale response requestId=...`) instead of reaching the model with the actual, specific failure reason (stderr excerpt, JSON-RPC error, etc.).

## Logs

**Core side** - `[MCP]` for the server-manager lifecycle, `[MCP_BRIDGE]` for the WebSocket transport to Windows:

```text
[MCP] connecting server=roblox host=WINDOWS transport=WINDOWS_BRIDGE
[MCP] connected server=roblox
[MCP] tools/list requested server=roblox
[MCP] discovered server=roblox tools=26 names=list_roblox_studios,search_game_tree,... durationMs=340
[MCP] call server=roblox tool=search_game_tree requestId=8639bc66-...
[MCP] completed server=roblox tool=search_game_tree success=true
[MCP_BRIDGE] Windows bridge registered session=cf206496-...
[MCP_BRIDGE] event=REQUEST_SENT session=cf206496-... type=MCP_LIST_TOOLS server=roblox requestId=a2dc405e-... timeoutMs=5000
[MCP_BRIDGE] event=RESPONSE_RECEIVED session=cf206496-... type=MCP_LIST_TOOLS server=roblox requestId=a2dc405e-... durationMs=11
[MCP_BRIDGE] listTools server=roblox toolCount=26 toolNames=list_roblox_studios,search_game_tree,...
[MCP_BRIDGE] activation finished server=roblox state=CONNECTED tools=26
```

A bounded empty-discovery retry and its eventual, explicit give-up look like this - never a silent
"0 tools" left uninvestigated:

```text
[MCP] discovery connected but empty server=roblox attempts=1/5
[MCP] discovery connected but empty server=roblox attempts=2/5
[MCP] discovery exhausted server=roblox attempts=5 - automatic retries stopped, manual reconnect required
```

**Windows side** - `[MCP-BRIDGE]` for both the WebSocket-facing bridge service and the per-server
process client, including the real OS PID for every process-lifecycle event:

```text
[MCP-BRIDGE] event=BRIDGE_CONNECTED session=1847293651
[MCP-BRIDGE] event=BRIDGE_REQUEST_RECEIVED type=MCP_CONNECT server=roblox requestId=...
[MCP-BRIDGE][roblox] event=PROCESS_STARTING command=cmd.exe /c cd /d %LOCALAPPDATA%\Roblox && .\mcp.bat
[MCP-BRIDGE][roblox] event=PROCESS_STARTED pid=24144
[MCP-BRIDGE][roblox] event=INITIALIZING pid=24144
[MCP-BRIDGE][roblox] event=MCP_INITIALIZED pid=24144 durationMs=329
[MCP-BRIDGE] event=BRIDGE_REQUEST_FINISHED type=MCP_CONNECT server=roblox requestId=... success=true durationMs=340
[MCP-BRIDGE][roblox] event=DISCOVERING pid=24144
[MCP-BRIDGE][roblox] event=TOOLS_LIST_RECEIVED pid=24144 durationMs=14 toolCount=26 toolNames=list_roblox_studios,search_game_tree,...
```

A stale process detected and transparently relaunched, and a full process-tree kill on a genuine
reconnect (real output from the real-process regression tests):

```text
[MCP-BRIDGE][roblox] event=STALE_CONNECTION_DETECTED pid=24144 reason=PROCESS_NOT_ALIVE - reinitializing
[MCP-BRIDGE][roblox] event=DESTROYING_DESCENDANT pid=16640 command=unknown
[MCP-BRIDGE][roblox] event=PROCESS_STOPPED pid=24144 exitCode=1 descendantsKilled=1
[MCP-BRIDGE][roblox] event=PROCESS_STARTED pid=31172
...
[MCP-BRIDGE][roblox] event=DESTROYING_DESCENDANT pid=2472 command=java.exe
[MCP-BRIDGE][roblox] event=DESTROYING_DESCENDANT pid=35172 command=conhost.exe
[MCP-BRIDGE][roblox] event=PROCESS_STOPPED pid=16736 exitCode=0 descendantsKilled=2
```

Secrets and large binary payloads are intentionally not logged - only counts, ids, PIDs, tool names,
timings, and sanitized bounded stderr excerpts.

The Windows bridge drains MCP process stderr continuously and keeps a bounded, sanitized diagnostics buffer. This prevents external MCP servers from blocking on a full stderr pipe while still surfacing useful startup and runtime errors.
