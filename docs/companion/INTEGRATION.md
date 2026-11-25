# Stage 3 isolated integration environment

Local REST base: http://127.0.0.1:18080/api/v1/companion. WebSocket: ws://127.0.0.1:18080/ws/companion. Contract 1.4.0. This is NONPRODUCTION, providerMode=SIMULATED. The launcher imports real companion task/plan REST/WS handlers, the native taskplan tool, ToolManager evidence handling, and a dedicated SQLite document store. It does not import production inference, external tools, conversation storage or knowledge indexes. Production main remains com.jarvis.core.JarvisApplication.

Test credentials: SERVER/data/companion-integration/credentials.json (Git-ignored), email companion@example.test with generated password/token. Read that local file; never commit its values. Authentication uses bearer headers for REST and WS. The mock fixed process token is not production authentication; its client expiresAt is advisory. Public health/bootstrap-status, login and authenticated /auth/me are supported. Other account-management and full conversation/voice APIs are outside this narrow launcher.

History: SERVER/data/companion-integration/history.db, configured explicitly through the launcher. It now survives integration restarts; durableHistory=true has been tested. All inference/actions remain explicitly simulated. No production SQLite/PostgreSQL database is touched. Keep this process loopback-only; an OS writer lock refuses concurrent recovery of its history file.

Build the separate NONPRODUCTION launcher from the backend checkout:

    mvn -Pcompanion-integration clean verify

Then start it (existing generated credentials/history are preserved):

    powershell -File scripts/start-companion-integration.ps1 -Java 'C:\Program Files\Java\jdk-21\bin\java.exe'

The script runs `javac-output-test/jarvis-companion-integration/jarvis-companion-integration-2.24.0-SNAPSHOT.jar` with generated test credentials and an absolute isolated history path. It checks the manifest and rejects a production Core JAR. `-Jar` accepts only the separate integration artifact; the old developer runtime-classpath and production-JAR PropertiesLauncher routes are retired. The launcher is physically outside jarvis-core and absent from production dependencies. It enforces loopback, defaults to port 18080 (`-Port` can select another local port), and uses its own configuration/profile. Do not enable this profile on the production application. API 1.4.0, credentials and fixture behavior are unchanged.

## Fixtures for Claude
- Normal POST /tasks retains the simple simulated response without a plan.
- Explicit message prefix [SIMULATED_PLAN] selects a deterministic integration fixture, not production task classification. It creates a two-step plan, starts actual fixture tool actions, inserts a third step, reorders, records verified receipts, updates steps, and completes the plan at revision 10 before task success. Labels/results visibly say SIMULATED. This can be cancelled while running.
- /tasks/{id}/plan and /plan/revisions expose owner-scoped plan state/history. /state and system.snapshot contain identical tasks/plans plus one atomic latestSequence and serverInstanceId. Count completed steps separately; never convert count/total to elapsed percentage.
- Plan event payloads retain ALL legacy Task fields and add plan, preserving existing task.* parsing. A 1.3 client can keep existing task behavior; plan rendering requires 1.4 support. New capabilities taskPlans and planEvidenceRequired advertise the feature.
- fixtures/plan-replay-1.4.0.json supplies duplicate/stale and out-of-order sequences with authoritative resync state; scripts/companion_plan_reference.py illustrates cursor/revision handling. Advance the cursor for every valid journal event, including unknown families; do not manufacture gaps by ignoring their sequence.
- After reconnect, compare instance IDs and resume from snapshot.latestSequence. An old-instance resume is rejected with system.reset_required. History deletion can scrub earlier frames, requiring state.sync.

## Verification
Live checks passed: capability flags and provider marker; native dynamic plan insertion/reorder; successful revision-10 plan with 3 verified steps; authenticated WS snapshot and plan updates; confirmed cancellation; deliberate crash/restart of only this isolated process; completed history restored, in-flight task FAILED/plan INTERRUPTED with no repeated tool action; old-instance reset and atomic snapshot; owner-authorized deletion and read-only confirmation that task/plan/revision/evidence rows are gone.

The extraction/dreaming services are unit-tested with mock providers and remain OFF; this minimal launcher does not import a memory model. Full-backend memory endpoints are documented in AUTONOMOUS_MEMORY.md. No GPU benchmark ran; the Linux harness remains pending owner approval of a load-test window. No production deployment or main merge is authorized.
