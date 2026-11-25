# Isolated companion integration environment

Local endpoint: http://127.0.0.1:18080/api/v1/companion; WebSocket: ws://127.0.0.1:18080/ws/companion. This is NONPRODUCTION and inference is SIMULATED. Task state, REST handlers, authentication gate, WebSocket frames, sequence/replay and cancellation use the real companion code. Task results explicitly carry [SIMULATED]. No production component scan, Ollama startup runner, external tools, SQLite or PostgreSQL store is imported; all task/history/event storage is process-local and disappears on restart.

Main class: com.jarvis.integration.CompanionIntegrationServer. The launcher enforces loopback binding, port 18080, a separate Spring configuration name, and the companion-integration profile. The production entrypoint is explicitly retained as com.jarvis.core.JarvisApplication. Do not enable the integration profile on the production application.

Generated credentials: SERVER/data/companion-integration/credentials.json (already ignored by Git). Email is companion@example.test; password/token are randomly generated per local setup, not committed or printed in SHARED. Claude can read this file locally for integration tests. Do not use real account passwords. Generate a new file with Python secrets.token_urlsafe(32) for password/token when setting up another machine. The server reads JARVIS_INTEGRATION_PASSWORD and JARVIS_INTEGRATION_TOKEN.

A developer classpath is prepared locally in the same ignored directory from a successful Maven test run. Start in a foreground terminal:

    powershell -File scripts/start-companion-integration.ps1 -Java 'C:\Program Files\Java\jdk-21\bin\java.exe'

For a packaged Spring Boot jar, pass -Jar with its absolute path; the script uses PropertiesLauncher with the integration main class, not the production entrypoint. Build/package first with the normal Maven reactor. The prepared developer-classpath route was smoke-tested here; the packaged-jar route has not yet been smoke-tested. On another machine without the prepared developer classpath, use the packaged jar route. Stop only this foreground process with Ctrl+C; never stop an existing production service.

POST /api/v1/auth/login accepts the test email/password and returns the usual accessToken/user/expiresAt shape. This test launcher uses one fixed process test token; expiresAt is advisory for the client, and it is not a replacement for production authentication. GET /api/health, GET /api/v1/auth/bootstrap-status and authenticated GET /api/v1/auth/me support connection/login checks. Other account-management endpoints are not part of this narrow test launcher. REST and WS require Authorization: Bearer TOKEN; WS query tokens are not supported here. Do not bind this mock-auth process to a network interface.

Claude handoff: /state is authoritative; system.snapshot has the identical shape, including serverInstanceId and latestSequence. Compare instance IDs; after reset replace state and resume from that cursor. Header bearer auth is preferred; production has no refresh endpoint. Contract is 1.3.0; old lifecycle fields remain compatible. Read providerMode=SIMULATED in capabilities and visibly identify the mock provider. Announcements are opt-in and this minimal launcher does not load a personality model; announcement event integration is covered by mocked-provider tests.

Smoke checks: unauthorized REST rejected, test login, authoritative snapshot, POST task, cancellation acknowledgement and final state; WebSocket connected/snapshot/resume. These are live transport checks against the local isolated process, not claims of GPU performance or live voice support.

Schema tooling: install scripts/companion-dev-requirements.txt into a local virtualenv or isolated target directory, then run python scripts/validate-companion-contract.py. It validates all versioned schemas and fixtures without fetching remote schema references.
