# Coding Agent OpenCode Integration

This integration runs OpenCode on the Windows desktop host while Core owns task
state, authorization, history and realtime events.

## Architecture

```text
JARVIS Windows UI
  -> authenticated REST/WebSocket
JARVIS Core on Ubuntu
  -> existing Windows bridge, server=coding
JARVIS Windows Coding Worker
  -> opencode run in the approved project directory
  -> Ollama OpenAI-compatible API on Ubuntu
```

Core never resolves `D:\...` paths on Ubuntu for Windows workspaces. The
Windows worker canonicalizes the selected project path, starts OpenCode with a
real working directory, captures stdout/stderr asynchronously, supports polling
and cancellation, and returns diagnostics through the same bridge used by other
Windows-hosted coding operations.

## Core Configuration

Use environment-specific overrides. Do not commit secrets or private addresses.

```yaml
jarvis:
  coding:
    opencode:
      enabled: true
      executable: opencode
      provider-name: jarvis-ollama
      base-url: http://SERVER_IP:11434/v1
      model: gpt-oss:20b
      request-timeout: 120s
      task-timeout: 6h
      max-concurrent-tasks-per-user: 1
      require-approval-for-dangerous-actions: true
```

The worker passes an isolated OpenCode config through `OPENCODE_CONFIG_CONTENT`;
it does not overwrite the user's global OpenCode config. The generated config
uses the OpenAI-compatible provider package, sets `provider/model`, and declares
a 65,536 token context limit.

## Ubuntu Setup

1. Install and start Ollama on the Ubuntu host.
2. Pull the model, for example `ollama pull gpt-oss:20b`.
3. Configure Ollama to listen only on a trusted network interface. Prefer VPN,
   LAN firewall rules, or a reverse proxy with authentication for anything
   outside localhost.
4. Set the context window to at least 64K when the model supports it, for
   example with a Modelfile `PARAMETER num_ctx 65536`.
5. Test the OpenAI-compatible endpoint:

```bash
curl http://SERVER_IP:11434/v1/models
```

6. Configure Core with `JARVIS_CODING_OPENCODE_BASE_URL` and
   `JARVIS_CODING_OPENCODE_MODEL`.
7. Restart Core and open `GET /api/v1/coding/diagnostics` from an authenticated
   client after the Windows bridge is connected.

## Windows Setup

1. Install Node.js if `npm` is not available.
2. Install OpenCode explicitly:

```powershell
npm install -g opencode-ai
opencode --version
```

3. Start the JARVIS Windows client and sign in.
4. Open `Kod`, choose a project folder with the folder picker, and register it.
5. Click `Diagnostyka`; verify worker, OpenCode, project directory, Ollama and
   model status.
6. Enter a task and click `Rozpocznij`. Live output appears in the same panel
   from `CODING_TOOL_OUTPUT` events.
7. Use `Zatrzymaj` to cancel; Core asks the worker to stop OpenCode, then marks
   the task cancelled.

## API

Existing workspace endpoints remain available. Project aliases were added for
the OpenCode UI flow:

- `POST /api/v1/coding/projects`
- `GET /api/v1/coding/projects`
- `DELETE /api/v1/coding/projects/{id}`
- `POST /api/v1/coding/tasks`
- `GET /api/v1/coding/tasks`
- `GET /api/v1/coding/tasks/{id}`
- `POST /api/v1/coding/tasks/{id}/cancel`
- `POST /api/v1/coding/tasks/{id}/reply`
- `POST /api/v1/coding/tasks/{id}/approve`
- `POST /api/v1/coding/tasks/{id}/reject`
- `GET /api/v1/coding/diagnostics`

All operational endpoints use the existing Bearer-token authentication and
owner checks in `CodingService`.

## First Safe Test

Use an approved scratch project directory and this task:

```text
Inspect the repository. Create a small file named jarvis-opencode-smoke.txt
with one line of harmless text, read it back, show git diff, then ask for
approval before deleting it. Finish with changed files, commands run, errors,
and remaining risks. Do not commit or push.
```

The current bridge can stop the process and record approval objects in Core, but
OpenCode non-interactive `run` does not yet provide a stable bidirectional
approval protocol through this integration. If deletion approval is needed,
reject or stop the task and delete manually after reviewing the diff.

## Troubleshooting

- `NOT_INSTALLED`: install with `npm install -g opencode-ai` and check PATH.
- `START_FAILED`: run `opencode --version` in PowerShell and inspect the error.
- Ollama unavailable: confirm `curl http://SERVER_IP:11434/v1/models` from
  Windows and check firewall binding.
- Model unavailable: pull the model on Ubuntu and re-run diagnostics.
- Long/noisy tasks: increase `task-timeout`, but keep output bounded; full
  project files are not uploaded to Core.

## Known Limitations

- OpenCode is launched through non-interactive `opencode run --format json`.
  Stdout/stderr are treated as logs; the integration does not execute commands
  parsed from stdout.
- True in-process OpenCode permission prompts and user replies need a stable
  OpenCode machine protocol or SDK. Core exposes reply/approval endpoints and
  events, but non-interactive OpenCode may fail or wait depending on its own
  permission behavior.
- Dangerous Git operations remain prohibited by the JARVIS coding tools. A
  direct OpenCode shell command is governed by OpenCode's own permissions and
  the safe system prompt; do not use `--dangerously-skip-permissions`.
