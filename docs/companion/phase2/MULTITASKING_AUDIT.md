# Audyt multitaskingu Core — 2026-11-25

> Implementation follow-up: PHASE2_IMPLEMENTATION.md and PHASE2_CORE_COORDINATION.md. The latest operator-qualified physical target is 24576; earlier numbers below are historical design/audit evidence.


## Zakres i pewność ustaleń

Audyt źródeł `SERVER`, branch `feature/companion-core`, commit `a0cc3c7bc9e16f71c716709216f42f1e514aeeae`. Bez zmian aplikacji Windows, konfiguracji produkcyjnej i wdrożenia. Pomiary sprzętowe pochodzą od operatora; nie wykonano inferencji na Ubuntu. `SERVER/HEAD` jest kopią historyczną i nie stanowi audytowanego drzewa. Surowy indeks mechanizmów: `concurrency-inventory.txt` (obejmuje także testy; `String.join` jest fałszywym trafieniem).

## Najważniejsze ustalenia

1. **Domyślna serializacja inferencji:** `jarvis-ollama/.../OllamaRequestCoordinator.java:34–36` ustawia `jarvis.model-gateway.concurrency:1`. Dwa workery Companion nie oznaczają dwóch inferencji. `chat-priority` preferuje oczekujący chat, ale nie przerywa już trwającej inferencji.
2. **Gateway nie obejmuje HTTP monitorem:** `acquire()` dodaje aktywny permit i wychodzi z `synchronized`; `OllamaProvider` wykonuje `HttpClient.send` oraz odczyt strumienia poza monitorem, w obrębie lease. Przy 2/3 slotach niezależne wątki mogą wysłać HTTP równocześnie. Brak starszego globalnego semafora otaczającego zwykły chat i native tool chat. Istniejące `OllamaGatewayConcurrencyTest` dowodzą liczby permitów, **nie dowodzą współbieżnego HTTP ani równoległego generowania**. Do przyszłego testu integracyjnego: blokujący serwer HTTP z barierą dla 2/3 żądań przez rzeczywisty provider, bez Mockito klienta HTTP.
3. **Ollama jest odrębną bramką:** wszystkie zaobserwowane sloty runnera mają ID 0, brak jawnego `OLLAMA_NUM_PARALLEL`. Nie stwierdzono równoległej inferencji. Przy zwiększeniu gateway nadal może istnieć kolejka wewnątrz Ollamy. Benchmark produkcyjny wykonuje operator niezależnie.
4. **Budżet wyjścia nie ogranicza generowania:** `ContextBudgetService.ollamaOptions()` przesyła `num_ctx` i `repeat_penalty`, bez `num_predict`. `reserved-output-tokens` zmniejsza wyłącznie szacowany budżet wejścia. Tytuł dziedziczy model/reasoning z `Brain` rozmowy; limit 60 znaków sprawdzany jest po inferencji. Raportowane 30,3 s i 1223 tokeny są zgodne z brakiem osobnego limitu; nie dowodzą jednej konkretnej przyczyny opóźnienia.
5. **Zasoby nie są GPU-aware:** wolny heap jest jedynie kontrolą JVM. `context-token-budget` liczy aktywne sloty × jeden skonfigurowany kontekst, bez wag modelu, KV, Whispera i faktycznej długości runnera. Snapshot nie obejmuje pełnego sprzętu.
6. **Brak asynchronicznego delegowania z rozmowy do Companion:** `SubagentTool` (`agent__run`) wykonuje `NativeToolLoopService.execute(child)` synchronicznie na wątku rodzica. Tworzy requestId i conversationId z sufiksem, ale nie tworzy niezależnego Companion taskId ani `task.created`. To wyjaśnia możliwość Overlay=0 podczas realnej pracy helpera. Sama wypowiedź modelu o uruchomieniu agenta pozostaje niezweryfikowana.
7. **Niezgodny kontekst produkcyjny:** deklarowane 262144 przekracza limit GPT-OSS 131072; Core dopuszcza około 232144 tokenów wejścia przed narzutem. Patrz projekt walidacji w `INFERENCE_PROFILES_DESIGN.md`. Źródłowy application.yml ma inne wartości (100000/2048); nie należy utożsamiać go z konfiguracją wdrożoną.

## Pipeline i zakres serializacji

REST chat/SSE lub WebSocket → `DefaultChatService` → `PipelineContextFactory` → `CognitivePipelineExecutor` → analiza/pamięć/knowledge/prompt → `ModelExecutionStage` (decyzja głównego modelu albo routing workflow bez dodatkowej decyzji LLM) → `NativeToolLoopService` / provider → gateway → Ollama HTTP → wynik/pamięć/tytuł. Etapy i rundy tool-loop są sekwencyjne **w jednym wykonaniu**; niezależne requesty mogą wejść równocześnie.

| Mechanizm / plik | Zakres i skutek | Decyzja |
|---|---|---|
| `OllamaRequestCoordinator` | singleton procesu/endpointu; monitor tylko admission/snapshot/release; oczekiwanie `wait()` zwalnia monitor | Zachować; dodać uczciwość kolejki i telemetryczne ID. Nie jest rozproszonym limitem kilku Core |
| `ModelGateway`, `AIJobType` | interfejs lease; CHAT/MAIN_MODEL interaktywne, MEMORY_AGENT/BACKGROUND/DEBUG pozostałe | Brak profili operacji; numeryczny priorytet zadania nie jest priorytetem inferencji |
| `InferencePriorityContext` | ThreadLocal zmienia typ na BACKGROUND w synchronicznym poddrzewie | Nie przechodzi sam na inne executory; jawnie przenosić owner/task/profile/deadline |
| `CompanionTasks` | domyślnie 2 workery; priority queue, globalny limit 128 nieterminalnych zadań; timer 1; monitor stanów wszystkich ownerów | HTTP chat poza monitorem. Operacje persistence/planów pod monitorem mogą opóźnić inne zadania. Timeout obejmuje kolejkę |
| `DynamicPlanService` | ten sam monitor co CompanionTasks, rewizje/evidence/persistence | Zachować spójność; nie usuwać jako rzekomej blokady inferencji |
| `DefaultConversationTitleService` | globalny executor 1, nieograniczona kolejka, brak jawnego shutdown; zadania różnych rozmów serializowane | Ograniczyć kolejkę, deduplikować conversationId i dodać deadline/lifecycle |
| `TaskAnnouncements` | worker 1, kolejka 128, opcjonalnie wyłączony; cached acknowledgment | Preferować tekst deterministyczny po rzeczywistym zdarzeniu. Cache nie uwzględnia owner/języka |
| `AutonomousMemoryPipeline` | worker 1 + kolejka 64, opt-in, jawny background context | Ekstrakcja poza monitorem memory; zachować bounded queue |
| `DefaultMemoryJobService` | osobna konfigurowalna bounded pula legacy; przy autonomous deleguje do nowego pipeline | Nie sumować obu jako zawsze aktywnych; MEMORY_AGENT podlega gateway |
| `AutonomousDreamingEngine` | scheduler sprawdza idle i snapshot, zleca maintenance na worker memory | Obecnie deterministyczne consolidate, **bez LLM**. Sprawdzenie idle ma wyścig, nie rezerwuje ciszy całego Core |
| `AutonomousMemoryService` / `SQLiteCompanionDocumentStore` | globalne monitory CRUD/konsolidacji; store także file lock pojedynczego writera | Chronią dane, nie trzymają permitu całej ekstrakcji; I/O może opóźniać stan/UI |
| `SubagentTool` | synchroniczny child loop, ThreadLocal depth=1; rodzic czeka na raport | Nie jest zadaniem asynchronicznym. Nie przechowuje permitu z poprzedniej rundy provider podczas narzędzia |
| `NativeToolLoopService` | lokalne rundy model→tool→model; nie ma globalnego locka całego execute | Sekwencyjność zależnych narzędzi jest poprawna; schema zależy od scope/workflow |
| `StdioMcpClient.request` | `synchronized` na klienta MCP przez write/read odpowiedzi; reader executor 1 i timeout future | Starsza realna serializacja narzędzi na jednym serwerze MCP, nie wszystkich inferencji; zachować do projektu demultipleksowania JSON-RPC |
| `HttpMcpClient`, `WindowsBridgeMcpClient` | synchronizacja lifecycle initialize/close | Nie stanowi globalnego limitu chat |
| `WebSocketWindowsMcpBridgeGateway` | `future.get(timeout)` na konkretnej komendzie; lifecycle monitor; wysyłka pod monitorem sesji | Blokuje wykonanie oczekujące na Windows, nie całą Ollamę |
| `ChatRunRegistry`, `JarvisWebSocketHandler`, `ChatController` | monitory run/session/SSE send; cached chat pool, timery heartbeat/expiry | Nie obejmują globalnie inferencji. Brak wspólnej pełnej blokady transakcji historii rozmowy: równoczesne tury tej samej rozmowy wymagają jawnej polityki |
| `CompanionSocket` | timer pompy 1, virtual delivery, monitor linku i snapshotu tasks | Zdarzenia/socket mogą czekać na stan/persistence; nie liczyć ich jako inferencji |
| `DefaultCodingService` | virtual executor, Future wykonania/anulowania konkretnego zadania | Oddzielny lifecycle coding; nie utożsamiać z rekordem Companion |
| `GmailClient` | virtual executor i `CompletableFuture.join` wyników fetch wiadomości | Czekanie lokalnego fan-out I/O, nie globalny lock modelu |
| `DefaultTemporaryWorkspaceService` | `ReentrantReadWriteLock` per workspaceId; write dla mutacji, read dla odczytów | Blokuje wspólny workspace, nie niezależne rozmowy; zachować ochronę plików |
| SSE `CompletableFuture.runAsync` / bridge activation | domyślny common pool; długie synchroniczne wykonanie może zajmować worker | Dodatkowa presja executora niezależna od gateway; projekt dedykowanego bounded executora zamiast zwiększania permitów |
| `VaultIndexer`, `KnowledgeFileWatcher` | pojedyncze scheduled executory index/embedding i debounce | Serializacja indeksowania w obrębie usługi; embedding konkuruje o zasoby |
| `TopkiMcModerationLimiter` | osobny semafor moderacji i rate limit klucza | Moderation client wykonuje generowanie poza wspólnym gateway: globalny activeInferenceRequests jest niepełny |
| debug/cache/output/source tracker, cipher key, Discord | krótkie monitory store/cache; Discord 4 workers i timer 2, stan kanału | Poboczne ograniczenia I/O; brak dowodu globalnej serializacji zwykłego chat |

Dodatkowe wejścia Ollamy: startup warmup, embedding provider i describeImage mają lease (niektóre zależności gateway są opcjonalne). `DefaultActiveModelService` wykonuje probe generowania i model switch poza wspólnym admission; moderation też ma własne wejście. `knowledge/vault/embedding/OllamaEmbeddingBackend` wysyła `/api/embed` przez własny `HttpJson`, bez ModelGateway; opcjonalny forceCpu nie usuwa kosztu RAM/CPU ani potencjalnego wpływu na scheduler. Metadane `/api/show`, `/api/tags`, `/api/ps` nie są inferencją i nie powinny zajmować slotu generowania.

## Delegowanie i prawdziwość komunikatów

`CompanionController.POST /api/v1/companion/tasks` tworzy trwały stan i event, worker prowadzi chat w `companion-<taskId>` pod `TaskExecutionContext` i owner context. Role wejściowe to TASK/RESEARCH, nie osobne wyspecjalizowane agenty z własnym runtime. Request/record nie przechowuje conversationId rozmowy zlecającej. Można rozmawiać równocześnie na poziomie wątków; przy gateway=1 chat czeka na aktywną inferencję tła.

Rozmowa ma możliwość wejścia w native tool-loop, gdzie `NativeToolSchemaMapper.resolveScope` wybiera schematy; workflow może je dodatkowo zawęzić, child usuwa `agent__*`. System prompt mówi o `agent__run` i o kontekście ordinary/companion, ale żadne z tych narzędzi nie tworzy Companion task. Ostateczny zestaw należy potwierdzić per request w `[NATIVE_TOOL_SCHEMA]`, nie zakładać dostępności wszystkich narzędzi w każdej turze. `taskplan__create` tworzy plan istniejącego zadania, nie zadanie.

Projekt następnego etapu: interfejs `CompanionTaskSubmission` w common, implementacja w api, narzędzie `companion__submit/get/cancel` w tools (bez zależności tools→api). Zwrócić taskId, sourceConversationId, executionConversationId i stan QUEUED dopiero po trwałym zapisie i skutecznym admission. Idempotency key `(owner, requestId, toolCallId)` zapobiega dubletom. Brak automatycznych retry efektów ubocznych. QUEUED znaczy „przyjęto”, RUNNING znaczy rozpoczęcie workera, dopiero inference.started znaczy aktywne generowanie. UI/komunikat o uruchomieniu generowany przez backend z receipt; zakaz twierdzenia „uruchomiłem” na podstawie samego tekstu LLM. To wymaga testów i implementacji kolejnej fazy; nie jest naprawione tym audytem.

## Pliki i testy kolejnej fazy

Modyfikacje: common/ai (profile, model limits, gateway telemetry), ollama/ContextBudgetService, AiContextProperties, OllamaProvider, DefaultActiveModelService, OllamaRequestCoordinator; api/companion/CompanionTasks, TaskAnnouncements, CompanionController/Socket; tools/runtime/SubagentTool, NativeToolLoopService/SchemaMapper oraz nowe narzędzie submission; memory/conversation/DefaultConversationTitleService i memory/autonomous. Kontrakt i wspólna diagnostyka: `RESOURCE_MANAGEMENT_DESIGN.md`. Nie zmieniono produkcyjnych klas.

Testy wymagane: rzeczywiste równoczesne HTTP przez provider (2/3); rozróżnienie gateway admission/Ollama slots; timeout/cancel/lease release przy błędzie i rozłączeniu streamu; izolacja tenantów i kontekstów child; serializacja tur tej samej rozmowy; admission niezależnych rozmów; brak starvation tła; pełna kolejka i rezerwa interactive; submission idempotent oraz brak fake started; reconnect/replay i restart; model-aware budgeting dla każdego endpointu. Benchmark sprzętowy osobno, bez deklarowania wyników na podstawie testów jednostkowych.
