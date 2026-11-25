# Production startup regression: integration controller isolation

Baseline: feature/companion-core, 7c4273f. Production main scans com.jarvis.
CompanionIntegrationServer was in jarvis-core/src/main/java; its nested Login
was independently discoverable as @RestController. @Profile on the enclosing
configuration did not apply to the nested controller. This caused duplicate
/api/health and auth mappings even without the integration profile. The existing
WebEnvironment.NONE context test never built servlet handler mappings.

## Fix boundaries

- Launcher moved to a separate jarvis-companion-integration Maven module, built
  only with -Pcompanion-integration; no production module depends on it.
- Nested mock-auth controller explicitly requires companion-integration profile.
- Production scan excludes the entire com.jarvis.integration namespace while
  retaining Spring Boot's standard type/autoconfiguration exclusion filters.
- Core Maven JAR packaging excludes that namespace as defense against old
  incremental-build .class files. Normal clean verify produces the deployment
  JAR directly; manual ZIP stripping/stage4-package.py is no longer required.
- Existing production HealthController, AuthController and CompanionController
  remain real beans; no production endpoint or Companion functionality removed.
- ProductionWebContextTest starts a full servlet context on a random loopback
  port, with actual controllers/auth and isolated storage. Only external Ollama
  HttpClient is replaced. An unprofiled duplicate-health poison fixture in
  com.jarvis.integration proves development components are rejected even if
  present on the production classpath. It remains test-only, never packaged.
- Audit of application main sources found no other dev/mock controllers outside
  the isolated namespace. Compiled artifact smoke recursively inspects embedded
  dependencies for integration classes and rejects Mockito/JUnit/test libraries.

## Java 25 tests

Spring Boot 3.3.7 managed Mockito 5.11/Byte Buddy 1.14.19. Override the existing
test dependency versions to Mockito 5.20.0 and Byte Buddy/agent 1.17.7; no Spring
Boot upgrade or application bytecode target change (release 21). Test JVMs use
the explicit Byte Buddy startup agent; no net.bytebuddy.experimental flag.

Official version rationale:
- https://github.com/mockito/mockito/releases/tag/v5.20.0 (Byte Buddy 1.17.7)
- https://github.com/mockito/mockito/issues/3647 (Java 25 support resolved)
- https://github.com/raphw/byte-buddy/releases/tag/byte-buddy-1.17.5 (ASM Java 25 support)

## Reproduction and verification

The new web-context test failed on baseline with duplicate /api/v1/auth/me
between AuthController and CompanionIntegrationServer.Login; the reported
/api/health collision has the same cause. Logs: workspace-root
companion-startup-baseline.log, companion-startup-java21.log and
companion-startup-java25.log. No tests disabled or excluded.

Build normally with clean verify. For restricted dynamic agent attachment,
set Surefire argLine to -javaagent:<local Maven repo>/net/bytebuddy/byte-buddy-agent/1.17.7/byte-buddy-agent-1.17.7.jar.

Packaged production startup smoke:

```text
python scripts/smoke-production-jar.py --java <java executable> --jar javac-output-test/jarvis-core/jarvis-core-2.24.0-SNAPSHOT.jar --report data/startup-smoke/production-report.json
```

Separate integration artifact smoke after -Pcompanion-integration build:

```text
python scripts/smoke-companion-integration.py --java <java executable> --jar javac-output-test/jarvis-companion-integration/jarvis-companion-integration-2.24.0-SNAPSHOT.jar --report data/startup-smoke/integration-report.json
```

These scripts create unique local test storage, strip inherited JARVIS/SPRING/
Ollama/JVM injection variables, use loopback ports and terminate only processes
they created. Production smoke exercises real bootstrap/login/auth/me,
anonymous denial, original health, authenticated Companion REST and WebSocket.
The model warmup uses a local HTTP stub, not GPU inference; it is not a
real-model quality/performance test or evidence of Ubuntu production recovery.
Integration smoke uses only generated ephemeral test credentials and its own DB.

API remains 1.4.0. Windows change: rebuild/use separate integration launcher;
REST/WS endpoints, task/plan schemas and production authentication are unchanged.
No automatic Ubuntu deployment/restart or main merge is part of this fix.

## Completed validation (2026-11-25)

- Baseline web-context reproduction: failed with ambiguous mapping, as expected.
- Full clean verify, Java 21: 960 tests, 0 failures/errors/skips.
- Full clean verify, Temurin Java 25.0.5.1+2, including optional integration module:
  960 tests, 0 failures/errors/skips; no experimental Byte Buddy flag.
- Full clean verify repeated with the server's Maven 3.6.3 and Java 25.0.5.1:
  960 tests, 0 failures/errors/skips. Final production/integration JAR smoke tests
  both passed again on these freshly built artifacts.
- Production executable JAR smoke on local Windows Java 25.0.5.1: PASS; real
  health/auth/bootstrap/login/me, anonymous REST/WS denied, authenticated
  Companion capabilities/state and WebSocket upgrade passed. API 1.4.0, LIVE.
- Separate integration executable JAR smoke on Java 25: PASS; test auth/REST/WS
  and three-step SIMULATED native plan completion passed on a separate port/DB.
- Production JAR SHA256:
  02BFF133C89DB35BB84763C6C51186FA54FF412CAA3CF31CCDED740856FB2680
  (final Maven 3.6.3 build).
- Smoke reports and per-process logs: ignored data/startup-smoke. Neither smoke
  used Ubuntu or production Ollama. All created Java processes were terminated.
- Existing invalid-password login is rejected with HTTP 400 by the global
  IllegalArgumentException advice handling a wrapped cause; protected anonymous
  routes use 401. Recorded as existing behavior, not changed in this startup fix.
- PowerShell launcher syntax parse and git diff --check passed. Source audit
  found the only named dev launcher in the new isolated module. No production
  credentials, Windows repository or Claude status files modified.

## Changed backend files

- pom.xml (opt-in module and supported test dependency versions)
- jarvis-core/pom.xml (production JAR exclusion)
- jarvis-core/src/main/java/com/jarvis/core/JarvisApplication.java (scan boundary)
- jarvis-core/src/test/java/com/jarvis/core/JarvisApplicationContextTest.java (clarified non-web coverage)
- jarvis-core/src/test/java/com/jarvis/core/ProductionWebContextTest.java (real web regression)
- jarvis-core/src/test/java/com/jarvis/integration/AccidentalIntegrationController.java (test-only poison fixture)
- jarvis-companion-integration/pom.xml (separate executable artifact)
- jarvis-companion-integration/src/main/java/com/jarvis/integration/CompanionIntegrationServer.java
  (moved from jarvis-core/src/main/java/com/jarvis/integration; nested profile and isolated port)
- scripts/start-companion-integration.ps1 (manifest guard and new artifact)
- scripts/smoke-production-jar.py
- scripts/smoke-companion-integration.py
- docs/companion/INTEGRATION.md
- docs/companion/PRODUCTION_STARTUP_FIX.md

Shared documentation maintained outside this backend Git repository:
CODEX_STATUS.md, DEPLOYMENT_REPORT.md, INTEGRATION_ENVIRONMENT.md,
PRODUCTION_STARTUP_FIX.md and WINDOWS_INTEGRATION_NOTICE.md. Schemas and
CONTRACT_VERSION.txt are unchanged.
