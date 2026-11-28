# Core — raport testów stabilizacji

## Bieżący etap
Pełna regresja: `stability-final-source-verified.log`, PASS. Dodatkowe sprawdzenie ostatnich zmian i pakowanie: `stability-artifact-verified.log`, PASS. Najnowsze raporty wszystkich klas: 1172 testów, 1168 wykonanych bez failures/errors, 4 pominięte opt-in. Końcowe pełne verify obejmuje również testy klasyfikacji REST i dwa testy kompletności identyfikatorów w routingu. Nie przedstawiać tego jako produkcyjnej akceptacji llama.cpp.

JDK 21, Maven offline, izolowany build `test-tmp/stability`. Testy protokołu używają lokalnych serwerów HTTP i syntetycznych ramek; nie są rzeczywistym modelem llama.cpp. Pełna regresja obejmuje wszystkie moduły reaktora potrzebne do jarvis-core. Nie edytowano Windows ani nie wdrażano Core.

## Macierz akceptacyjna
Ollama rzeczywista: `stability-real-final-source.log` i `real-ollama-audit.json` w wydaniu. llama.cpp rzeczywista: ENVIRONMENT_LIMITATION (brak procesu/plików/docelowego GPU). Testy adaptera llama.cpp i kontekstu 8192: PASS w zakresie protokołu, nie produkcji.

1. Powitanie; 2. Tożsamość; 3. Rozmowa wieloturowa; 4. Narzędzie; 5. Pogoda; 6. Waluta; 7. Pogoda/USD/BTC/CSS; 8. Pięć maili Gmail na koncie testowym; 9. Agregacja; 10. Rozmowa podczas workerów; 11. Obraz; 12. Dokument; 13. Coding w testowym workspace; 14. Pamięć użytkownika; 15. Anulowanie; 16. Reconnect; 17. Restart; 18. Zmiana silników; 19. Długi kontekst; 20. Awaria providera; 21. Izolacja kont; 22. Współbieżność.

Statusy: PASS / FAIL / BLOCKED / NOT_VERIFIED / NOT_IMPLEMENTED / ENVIRONMENT_LIMITATION. Zapisuj wersję/artefakt, polecenie, źródło dowodu i zakres testu.

## Audyt istniejących modułów

| Moduł | Ustalenie / granica audytu | Dowód lokalny / pozostała granica |
|---|---|---|
| System prompt i osobowość | Tożsamość, ustawienia użytkownika i folderu zachowane; nowy system native nie zawiera kopii bieżącej wiadomości i historii. | RoutingContextBudgetRegressionTest, MainConversationSynthesisTest; rzeczywiste powitanie i tożsamość. |
| Routing, research, planning, coding | Brain/configurationId i aktywny provider; brak cichego przeprzypisania. Samodzielny jarvis-planner jest tylko interfejsem. | InferenceSwitchProtocolTest, LlamaCppInferenceEngineTest, SemanticOrchestratorTest, testy coding. Pełna produkcja llama NOT_VERIFIED. |
| Pamięć autonomiczna | Właściciele, dane wrażliwe, rewizje, deduplikacja i sprzeczności; ekstrakcja używa aktywnego providera. | AutonomousMemoryTest, testy memory agent i SQLite. Rzeczywista ekstrakcja na lokalnej Ollamie, zapis po restarcie i pusta lista drugiego konta PASS w oddzielnym izolowanym profilu enabled=true. Produkcja NOT_VERIFIED. Włączenie tych rekordów do głównego promptu nie istnieje w MemoryRetrievalStage (NOT_IMPLEMENTED). |
| Dreaming | Istniejąca opt-in konsolidacja z kontrolą bezczynności; bez oddzielnego wywołania LLM i bez modyfikowania wag. | AutonomousDreamingEngineTest. Długoterminowy produkcyjny harmonogram NOT_VERIFIED. |
| Knowledge Vault | Polityka ścieżek, plików promptów, indeksów, edycji, migracji, wyszukiwania. Ochrona GPU embeddingów i izolacja draftów naprawione. | Testy vault/embedding i KnowledgeDraftOwnerIsolationTest; rzeczywisty dokument w rozszerzonej próbie. Wspólny katalog nie jest prywatnym Vaultem per konto. |
| Historia, foldery, projekty/workspaces | SQLite, rozdzielenie użytkowników/rozmów, transakcje i tymczasowe załączniki. | Testy conversation/migrations/workspace + rzeczywiste konta i wieloturowość. Oddzielny duży Project Engine nie był dodawany. |
| Task Planning & Tracking | Istniejące SemanticOrchestrator, DynamicPlanService, trwałe wyniki/rewizje/zależności i ograniczone pomocniki. | SemanticTaskLifecycleTest, TaskAutonomyIntegrationTest, AuthorizationIndependentTasksTest; realne cztery workery i anulowanie. |
| Voice STT/TTS | Istniejący transport OpenAI-compatible audio i brak fałszywych sygnałów zakończenia. Audio nie jest generacją LLM i nie przełącza się na chat model. | VoiceServiceTest i testy Companion voice. Realny mikrofon, odtwarzanie i usługa audio ENVIRONMENT_LIMITATION; nie uruchamiano dźwięku. |
| Vision i załączniki | Aktywny VisionDescriptionProvider, kontrola możliwości, pochodzenie obrazów, limity i bezpieczne workspace. | Testy obu adapterów obrazów, pipeline image/attachment gates, rzeczywistego pliku tekstowego; lokalny model bez VISION, realna analiza obrazu ENVIRONMENT_LIMITATION. |
| SearXNG / web research | Dedykowane pogoda/NBP, kompaktowe wyniki, pełne materiały poza kontekstem, paginacja, zatrzymanie niedostępnego źródła. | InformationToolsTest, InformationContextPipelineTest, web/SSRF/read-progress tests; rzeczywista pogoda/USD. SearXNG lokalnie niedostępne, BTC/CSS nie potwierdzone. |
| Gmail/OAuth | Istniejące refresh/authorization states, oddzielenie zgody od odczytu i niezależność zadań. | GmailToolTest, testy integration permissions/authorization. Pięć maili na autoryzowanym koncie testowym BLOCKED — brak takiego konta/tokenów. |
| Google Calendar / Tasks | Odczyty i zapisy pozostają odrębne; Tasks nie jest orkiestratorem agentów. | CalendarToolTest, testy schemas/permissions; żywe konto Google BLOCKED. Nie tworzono zdarzeń ani zadań na koncie użytkownika. |
| Spotify | Istniejący workflow, izolacja stanów/połączeń i ochrona przed powtórzeniem skutków ubocznych. | Testy NativeToolLoopServiceSpotify* i runtime. Rzeczywisty Spotify/urządzenie/Bridge ENVIRONMENT_LIMITATION; nie sterowano odtwarzaniem. |
| MCP / Roblox | Istniejące stdio/HTTP/Windows Bridge, discovery, timeout, reconnect i jawne braki źródeł. | Testy McpClient/Manager/WindowsBridge lifecycle i Roblox continuation. Produkcyjny Roblox serwer/Bridge ENVIRONMENT_LIMITATION. |
| Windows Coding Bridge / Coding Agent | Istniejące uprawnienia, ryzyka, idempotencja i jawne oczekiwanie na zatwierdzenie; nie usuwano ograniczeń. | Testy DefaultCodingService/CodingTool, JarvisAuthInterceptorCodingTest i WebSocketWindowsMcpBridgeGatewayTest. Rzeczywiste wykonanie Windows ENVIRONMENT_LIMITATION. |
| Auth i izolacja kont | REST/WS autoryzowane; inference ADMIN; prywatne taski i drafty sprawdzają właściciela przed zmianą. | JarvisAuthServiceTest, nowe draft isolation i rzeczywiste 401/403/404/pusta obca lista. |
| Moderation API | Naprawiono obejście aktywnego silnika; parser biznesowy, deadline i brak wykonywalnych narzędzi zachowane. | DefaultTopkiMcModerationServiceTest, OllamaModerationModelClientTest (aktywny llama → zero żądań Ollamy). Rzeczywisty model moderacji na llama NOT_VERIFIED. |
| Diagnostyka i zasoby | Cumulative vs per-call/occupancy, heurystyki jawnie opisane, VRAM fail closed, brak fikcyjnego reasoning/progress. | Token/gateway/context tests, REST state proof, realna telemetria. Benchmark GPU z Ubuntu pochodzi od użytkownika, nie z tej próby. |
| Trwałość i awarie | SQLite, journal/replay, restart profilu i zadań, bez ponawiania skutków ubocznych w recovery. | Testy SQLite/lifecycle i rzeczywisty WS+restart. Restart produkcyjnego llama.cpp i GPU rollback ENVIRONMENT_LIMITATION. |

## Klasyfikacja brakujących elementów

- Samodzielny `jarvis-planner` (PlannerService/PlannerTask/PlannerResult bez implementacji): NOT_IMPLEMENTED; nie mylić z istniejącym planowaniem agentów.
- Odrębny zapis profilu nieaktywnego bez aktywacji: NOT_IMPLEMENTED. Istniejący endpoint aktywuje pełne parametry i zapisuje profil po gotowości.
- Adaptive UI Engine: NOT_IMPLEMENTED / wyłącznie dokumentacja, zgodnie z wymaganiem.
- Wykonywalne drafty wiedzy są trzymane w pamięci procesu; automatyczna rekonstrukcja ich działań po restarcie nie istnieje. Nie dodawano automatycznego stosowania pozostawionych plików `.draft`.
- Surowe prywatne THINKING_TOKEN są celowo pomijane przez istniejącą granicę Core; nie usunięto tej ochrony w celu „zaliczenia” UI.

## Końcowa macierz akceptacyjna — 2026-11-28

Wszystkie pozycje rzeczywistego produkcyjnego llama.cpp: **ENVIRONMENT_LIMITATION / NOT_VERIFIED**. Poniżej status lokalnej Ollamy i wyraźna granica próby.

| # | Scenariusz | Status lokalny | Dowód / zakres |
|---|---|---|---|
| 1 | Powitanie | PASS | Rzeczywisty SSE i model Ollama. |
| 2 | Tożsamość | PASS | Rzeczywisty SSE, zachowana osobowość. |
| 3 | Rozmowa wieloturowa | PASS | BURSZTYN-742 odtworzony w tej samej rozmowie. |
| 4 | Narzędzie | PASS | Rzeczywisty pipeline narzędzia pogodowego. |
| 5 | Pogoda | PASS | Open-Meteo, kompaktowy wynik i legenda WMO; testy godzin/dni w regresji. |
| 6 | Waluta | PASS | Rzeczywisty USD/NBP w złożonym poleceniu. |
| 7 | Pogoda/USD/BTC/CSS | ENVIRONMENT_LIMITATION | Cztery workery, dwa wyniki i dwie jawne awarie WEB_SEARCH_FAILED. Obsługa częściowego wyniku PASS; pełne dane BTC/CSS niedostępne bez SearXNG. |
| 8 | Pięć maili Gmail | BLOCKED | Brak autoryzowanego konta testowego. Testy protokołu/OAuth nie są odczytem prawdziwej skrzynki. |
| 9 | Agregacja | PASS | Jedna odpowiedź PARTIALLY_COMPLETED; brak dopisanej giełdy w lokalnej próbie, nie gwarancja dowolnych faktów modelu. |
| 10 | Czat podczas workerów | PASS | Rzeczywista odpowiedź na osobne powitanie podczas pracy workerów. |
| 11 | Obraz | ENVIRONMENT_LIMITATION | Lokalny gpt-oss:20b nie deklaruje VISION; produkcyjny projector/model llama niedostępny. Adaptery/gates sprawdzone regresją. |
| 12 | Dokument | PASS | Dokładny finalny JAR: rzeczywisty odczyt Knowledge Vault, kod 271828 i data 2031-02-03; 11.859 s, 3 wywołania modelu. |
| 13 | Coding Agent | ENVIRONMENT_LIMITATION | Testy izolowanego workspace/protokołu PASS; brak działającego Windows Coding Bridge do rzeczywistego wykonania. |
| 14 | Pamięć użytkownika | PASS | Rzeczywista ekstrakcja, zapis, restart i izolacja w profilu autonomous.enabled=true. Pobieranie tych rekordów do głównego promptu NOT_IMPLEMENTED. |
| 15 | Anulowanie | PASS | Rzeczywiste CANCEL_REQUESTED → CANCELLED. |
| 16 | Reconnect | PASS | Rzeczywisty WebSocket: snapshot, events.resume, system.resumed, reset_required po restarcie. |
| 17 | Restart | PASS | Zapisany wybór Ollamy, CANCELLED i rekord pamięci przeżyły restart. Konfiguracja seedowana; mutacja wyboru nie była częścią tej próby. |
| 18 | Przełączanie silników | ENVIRONMENT_LIMITATION | Regresja protokołu/rollback/STALE PASS; rzeczywiste Ollama→llama→Ollama i zwolnienie VRAM niezweryfikowane bez llama. |
| 19 | Długi kontekst | PASS | Finalny JAR / 8192: 4 tury po 4184 znaki oraz recall STAB-LONG-4, brak błędów; największy rzeczywisty input jednego wywołania 3257. Nie jest to test dowolnej informacji ze środka starej długiej wiadomości. |
| 20 | Awaria providera | PASS | Finalny JAR: rzeczywisty niedostępny endpoint → 503/SSE ERROR; nadmiar kontekstu → 422; brak fikcyjnych assistant messages i busy=false. |
| 21 | Izolacja kont | PASS | Rzeczywiste 401/403/404 i pusta obca lista tasków/pamięci; osobny test izolacji draftów. |
| 22 | Współbieżność | PASS | Cztery aktywne logiczne workery, dwa permit-y inference; nie dowodzi równoległości GPU ani benchmarku produkcyjnego. |

## Artefakt i dowody końcowe

JAR: `SHARED/releases/2026-11-28-stability/jarvis-core-2.24.0-stability.jar`.
SHA256: `a27bdd551c3c33eafc0179f951c3a11746a2d1f0681db08ab725acf8af78188c`.
Pełna regresja: `stability-final-source-verified.log`; końcowa paczka zmian i package: `stability-artifact-verified.log`. Łączne najnowsze raporty XML: 1172 testów, 1168 wykonanych, 0 failures, 0 errors, 4 opt-in pominięte. Rzeczywista akceptacja jest osobnym uruchomieniem `RealIngressAuditTest` (log `stability-real-final-source.log`), nie jest wliczana jako kolejne 1172 testów.

W wydaniu zapisano surowe JSON: real-ollama-audit, extended-audit (SSE i metryki), memory-audit, ws-restart-audit, fault-audit oraz inference-state-http. Rzeczywisty GET state finalnego JAR: ADMIN 200, schemat obu silników i płaska mapa 24 pól llama; bez sesji 401 i zwykły USER 403 potwierdzone osobnym live ingress.

Czasy i pomiary końcowej próby podano poniżej oraz w performance-summary.json; szczegółowe SSE zachowano w extended-audit.json.

## Pozostałe ryzyka i granice

- Ograniczone referencje historii przechowują początek i koniec; pełna rozmowa pozostaje w SQLite. Automatyczne odtworzenie dowolnego faktu ze środka bardzo starej długiej wiadomości **NOT_VERIFIED**; próba potwierdza tail recall, nie bezstratność streszczenia. Pełne wyniki narzędzi mają odrębny istniejący ToolOutputStore/output read/grep; nie utożsamiać go z automatycznym przeszukiwaniem pełnej historii rozmowy.
- Brak usług/kont: SearXNG, Gmail/Google/Spotify, audio i Windows/Roblox Bridge; właściwe próby produkcyjne wymagają tych środowisk. Nie wdrażano, nie wysyłano wiadomości ani nie modyfikowano kont.
- Prywatne raw reasoning nadal jest chronione; nie dodano fikcyjnego postępu ani tokenów. Native argumenty odpowiedzi są publikowane dopiero po walidacji.

## Końcowe pomiary dokładnego JAR

| Próba | Czas [s] | Wywołania modelu | Maks. input per-call | Input łącznie | Output wygenerowany |
|---|---|---|---|---|---|
| document | 11.859 | 3 | 1972 | 5420 | 249 |
| long-1 | 4.0 | 1 | 2874 | 2874 | 52 |
| long-2 | 4.359 | 1 | 3069 | 3069 | 64 |
| long-3 | 4.047 | 1 | 3255 | 3255 | 47 |
| long-4 | 3.875 | 1 | 3257 | 3257 | 42 |
| long-recall | 5.437 | 1 | 2099 | 2099 | 110 |

## Dodatkowe wykryte regresje i granice dowodów

Ostatni audyt wykrył pominięcie podmiotów w planie (2 zamiast 4 workerów). Dodano ogólną kontrolę jawnych identyfikatorów wieloelementowego planu, bez klasyfikowania intencji listą słów. Niepoprawna decyzja trafia do istniejącej ograniczonej korekty przed admission. Dwa testy regresji i rzeczywisty pipeline czterech workerów PASS po poprawce; nie dodano wywołań do poprawnej prostej decyzji.

Osobna próba dokumentu FAIL ujawniła wybór browser zamiast knowledge. Doprecyzowano własność źródła w polityce semantycznej: dokumenty Knowledge Vault są serwerowe i nie wymagają sesji Windows. Po tej zmianie ponownie wykonano pełne verify, rzeczywisty ingress oraz odczyt dokumentu i długą rozmowę na dokładnym finalnym JAR. Nie zmieniano ani nie uruchamiano Windows UI.

evidence-provenance.json rozróżnia finalny JAR, aktualny kod testu ingress i poprzedni artefakt 19fde... z testów WS/awarii/pamięci (przed samym doprecyzowaniem źródeł). Nie przypisuje starszej próby nowemu plikowi. Pełna końcowa regresja obejmuje wszystkie te niezmienione ścieżki. Historyczne nieudane próby zachowane osobno; statusy PASS mają opisany zakres, a nie gwarancję dowolnej odpowiedzi modelu.
