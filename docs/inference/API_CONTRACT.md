# Kontrakt inference 1.9.0 — instrukcja dla Claude

Data: 2026-11-27. Zmiana addytywna względem dostarczonego Core 1.8.0. Kod Windows nie został zmieniony. Core nadal obsługuje `/api/models`, `/api/models/active`, istniejące REST rozmów i oba WebSockety. Legacy POST /api/models/active zachowuje format odpowiedzi, wymaga aktywnego ADMIN i przechodzi przez ten sam transakcyjny manager, wybierając Ollamę. Nie omija blokady rezydencji. Starsze klienty ignorują nieznane pola i rodziny zdarzeń. Companion ogłasza wersję 1.9.0; wewnętrzna rewizja multipleksowania `/ws/jarvis` pozostaje 1.6.0.

## Architektura i granice

`AIProvider` pozostaje wspólnym interfejsem generowania: `ModelMessage`, `ModelResponse`, `ModelUsage`, `ModelToolCall`, `ModelStreamEvent`. `ModelGateway` oznacza admission, nie transport. OllamaProvider pozostaje dotychczasowym adapterem. Nowy `LlamaCppInferenceEngine` implementuje ten sam interfejs i używa `/v1/chat/completions` SSE. `InferenceControl` jest granicą zarządzania; `InferenceEngineManager` odpowiada za wybór, walidację, gotowość, trwały zapis i rollback. Rejestr dostawców wykorzystuje istniejącą listę Spring `AIProvider`; dodanie silnika nie wymaga zmiany parsera Windows. Nowe klasy znajdują się w pakiecie `com.jarvis.inference` istniejącego modułu jarvis-ollama, aby nie zmieniać grafu modułów.

`EngineAccessGate` jest wspólną blokadą rezydencji modeli w pojedynczym JVM. Zmiana silnika uzyskuje wyłączną blokadę i czeka do 30 s na zakończenie dzierżaw HTTP. Koordynator Ollamy zachowuje priorytety i FIFO. llama.cpp ma jeden slot; kolejne żądania czekają do 30 s. Długie generacje, które nie zwolnią dzierżawy, kończą zmianę błędem ENGINE_BUSY, bez zabijania pracy. Nie są kopiowane ani ponownie wykonywane narzędzia.

Brain zawiera opcjonalne `configurationId`. BrainCatalog odczytuje przypięty `InferenceSelectionContext` albo bieżący wybór. Task Engine zapisuje wybór w dokumentach `task-inference` i przywraca go po restarcie; kolejne rundy nie przechodzą potajemnie na inny model. Przestarzała konfiguracja kończy rundę błędem STALE_CONFIGURATION. Historia, pamięć, plany, wyniki i identyfikatory rozmów nie są migrowane ani czyszczone. Jawne przypisanie wewnętrzne: `InferenceSelectionContext.call(selection, action)`. Nie jest to mechanizm równoczesnego uruchamiania różnych modeli na GPU.

CPU embeddingi pamięci przy llama.cpp nadal używają Ollamy z `options.num_gpu=0` i `keep_alive=0s`, we wspólnej dzierżawie CPU. Moderacja i rozgrzewanie Ollamy nie mogą załadować jej modelu GPU, kiedy aktywne jest llama.cpp. Odrębny, wyspecjalizowany pipeline TopkiMC pozostaje transportem Ollamy i wtedy zgłasza błąd nieaktywnego silnika. STT/TTS nie są zatrzymywane.

## Uwierzytelnianie

Wszystkie endpointy poniżej wymagają istniejącego Bearer tokenu oraz aktywnego konta `role=ADMIN`. Interceptor ustawia konto po weryfikacji tokenu; pole role przesłane w JSON niczego nie autoryzuje. Brak tokenu: 401; brak uprawnień: 403. Zwykłe generowanie nadal korzysta z istniejących uprawnień rozmów. HTTPS/WSS poza lokalnym developmentem. Nie wysyłaj tokenów w query string.

## Endpointy

Wszystkie ścieżki względne do `/api/v1/inference`, JSON UTF-8.

| Metoda | Ścieżka | Żądanie / odpowiedź |
|---|---|---|
| GET | /state | State: wybór, status, revision, operationId, engines, resources, metrics, parameterSchema |
| GET | /engines | tablica wpisów instalacji/procesu |
| GET | /installation | status instalacji llama.cpp, wymagania i bieżąca operacja instalacji |
| POST | /installation | bez body; start stałego skryptu instalacyjnego, zwraca operację |
| GET | /models?engine=ollama lub llamacpp | tablica ModelDescriptor |
| GET | /configuration | Selection |
| POST | /selection | SwitchRequest → SwitchResult; odpowiedź po gotowości lub błędzie |
| PUT | /configuration | ten sam SwitchRequest; pełne zastąpienie parametrów, przeładowanie modelu |
| POST | /process | {"action":"start" lub "restart" lub "stop"}; tylko aktywne llama.cpp |
| POST | /downloads | DownloadRequest → DownloadOperation, asynchronicznie |
| GET | /operations | operacje pobierania bieżącego właściciela w tym procesie Core |
| POST | /operations/{operationId}/cancel | anulowanie pobierania bieżącego właściciela |
| POST | /models/import | {"path":"relative/model.gguf","sha256":"64 hex"} → wynik weryfikacji bez kopiowania |
| DELETE | /models?engine=...&model=... | 200 bez body; aktywnego modelu nie można usunąć |
| GET | /resources | próbka zasobów hosta |
| GET | /logs | maks. 200 ostatnich linii stdout/stderr własnego llama-server |
| POST | /benchmark | bez body; pojedyncza próba z ustalonym promptem i etykietą obserwowanej rezydencji |

Operacje zarządzania błędne dla backendu: HTTP 409, `{"code":"ENGINE_SWITCH_FAILED","message":"ENGINE_SWITCH_FAILED: ..."}`. Brak usług w izolowanym launcherze testowym: 503. Nie ponawiaj automatycznie POST /selection po zerwaniu HTTP: odczytaj /state, gdyż przełączenie mogło się zakończyć. Benchmark, import i przełączenie są synchroniczne; instalacja i pobieranie są asynchroniczne. Instalacja ma timeout 30 minut i nie restartuje Core. Wywołuje tylko skonfigurowany przez operatora plik instalatora; API nie przyjmuje komendy, argumentów powłoki ani URL instalatora.

## Schematy i przykłady

Schematy draft 2020-12: `schemas/1.9.0/inference.schema.json`. Przykłady: `schemas/1.9.0/inference.examples.json`. Te same pliki znajdują się w `SERVER/docs/inference/schemas/`.

```json
{"engine":"llamacpp","model":"example-Q4_K_M.gguf","parameters":{"contextSize":4096,"maxOutputTokens":1024,"gpuLayers":0,"parallelSlots":1,"timeoutSeconds":120,"jinja":true,"declaredCapabilities":["TOOLS","STRUCTURED_OUTPUT"]}}
```

To jest przykład deklaracji operatora, nie potwierdzenie możliwości dowolnego GGUF. Domyślnie CPU (`gpuLayers=0`), kontekst 4096, jeden slot. GPU wymaga dodatkowo jawnego, zmierzonego `gpuBudgetMiB`; Core sprawdza wolne VRAM po zwolnieniu Ollamy, budżet + 2048 MiB zapasu i co najmniej 8 GiB dostępnego RAM, jeśli pomiar RAM jest dostępny. Ten budżet nie jest automatyczną estymacją modelu/KV cache. Obsługiwane parametry i ich zakresy UI pobiera z `state.parameterSchema.llamacpp`; nie pokazuje opcji poza schematem. Ollama korzysta z istniejących profili inference; nowy endpoint odrzuca niepuste parametry dla Ollamy. Wszystkie parametry llama.cpp zmieniane są przez pełne przeładowanie; nie zakładaj edycji na żywo bez reloadu.

Selection ma dokładnie `configurationId`, `engine`, `model`, `parameters`. SwitchResult ma `operationId`, `selection`, `status`. State zawiera `contractVersion=1.9.0`, `selection`, `status`, `error`, `metrics`, `engines`, `resources`, `parameterSchema`, `revision`, `operationId`. Pole `error` może pozostać niepuste po udanym rollbacku: UI pokazuje przyczynę odrzuconej zmiany i rzeczywisty przywrócony wybór.

ModelDescriptor: `id`, `engine`, `sizeBytes`, `declaredCapabilities`, `verifiedCapabilities`. Dla GGUF dodatkowo `status=AVAILABLE|INVALID`, `error`. Ollama deklaruje enumy TEXT/VISION/TOOLS/THINKING z istniejących metadanych; GGUF discovery deklaruje jedynie TEXT/STREAMING. `verifiedCapabilities` jest obecnie pustą tablicą — testy syntetyczne adaptera nie weryfikują możliwości modelu. Deklaracje ustawień `declaredCapabilities` przyjmują VISION/TOOLS/STRUCTURED_OUTPUT/REASONING. VISION wymaga istniejącego mmproj. Nie wyciągaj możliwości z nazwy repozytorium ani rozszerzenia pliku. Import sprawdza nagłówek GGUF, komplet shardów i SHA wskazanego pliku; nie jest pełnym testem tensorów ani wszystkich możliwości. Poszczególne shardy wymagają osobnych hashy przy pobraniu.

Pliki GGUF: katalog operatora `/opt/jarvis/models/llamacpp`; ścieżki są kanonizowane i muszą pozostać wewnątrz katalogu. Model wieloczęściowy wybiera się przez shard `-00001-of-NNNNN.gguf`; pozostałe shardy muszą istnieć z prawidłowym nagłówkiem. Shardy inne niż pierwszy są pomijane w selektorze. Usuwanie całego shardowanego modelu wymaga operatora, aby uniknąć częściowego usunięcia. `mmproj` i `chatTemplate` także muszą wskazywać pliki w katalogu modeli. Szablon jest plikiem; UI nigdy nie wysyła wykonywalnej komendy.

DownloadRequest dla HF:
```json
{"engine":"llamacpp","repository":"organization/repository","revision":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","file":"model-Q4_K_M.gguf","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
```

Wymagane: rzeczywisty commit HF (40 hex), konkretny basename GGUF (wybór kwantyzacji przez plik) i znany SHA256. Core buduje HTTPS URL Hugging Face. Nie ma endpointu wyszukiwania HF ani obsługi prywatnego tokenu HF. Wybór repozytorium/rewizji/pliku jest jawny. Pobierz osobno wszystkie shardy i mmproj. Dla Ollamy: `{"engine":"ollama","file":"gpt-oss:20b"}`; pozostałe pola nieużywane. Jedno aktywne pobieranie, kolejka ograniczona; zachowywane do 128 operacji w pamięci procesu. Operacja ma operationId, revision, engine, model, status, downloadedBytes, nullable totalBytes, bytesPerSecond, nullable percent, nullable etaSeconds i error. HF używa Range i weryfikuje Content-Range; 200 przy wznowieniu oznacza bezpieczne rozpoczęcie od zera. `.part` nie trafia do discovery. SHA mismatch nie publikuje modelu. Po restarcie Core lista operacji jest pusta, `.part` zostaje: ponów identyczne DownloadRequest, aby wznowić. Ollama zarządza własnym cache i wznowieniem; łączny total jest nieznany do zakończenia, dlatego UI pokazuje wtedy postęp nieokreślony. Anulowanie zachowuje `.part` i nie usuwa istniejących modeli.

## Statusy i błędy

Globalnie: READY, SWITCHING, LOADING_MODEL, RECOVERING, STOPPED, ERROR. Instalacja: IDLE, INSTALLING, COMPLETED, ERROR; wykrycie binarki: INSTALLED/NOT_INSTALLED; Ollama: EXTERNAL. Pobieranie: QUEUED, DOWNLOADING, VERIFYING, COMPLETED, CANCELLED, ERROR. Nie wyświetlaj syntetycznego procentu zmiany silnika. Nie wszystkie proponowane w specyfikacji statusy są emitowane w tej implementacji.

Ważne kody: UNKNOWN_ENGINE, MODEL_REQUIRED, NOT_INSTALLED, MODEL_NOT_INSTALLED, MODEL_NOT_FOUND, INVALID_MODEL_PATH, INVALID_GGUF_HEADER, INVALID_MODEL_SHARDS, INCOMPLETE_MODEL, UNSUPPORTED_PARAMETERS, INVALID_PARAMETER, CAPABILITY_REQUIRED, PROCESS_MANAGEMENT_DISABLED, PORT_IN_USE, ENGINE_BUSY, ENGINE_INACTIVE, ENGINE_SWITCH_FAILED, STALE_CONFIGURATION, MODEL_LOAD_FAILED, OUT_OF_MEMORY, MODEL_LOAD_TIMEOUT, MODEL_READINESS_MISMATCH, ENGINE_PROCESS_EXITED, ENGINE_UNHEALTHY, CONFIGURATION_RESTORE_FAILED, EMPTY_RESPONSE, REASONING_ONLY_RESPONSE, INCOMPLETE_GENERATION, INVALID_TOOL_ARGUMENTS, INVALID_TOOL_CALL, CONFLICTING_SSE_EVENT_ID, SSE_FRAME_TOO_LARGE, CANCELLED, INFERENCE_TRANSPORT_FAILED, BACKEND_HTTP_<status>, CHECKSUM_MISMATCH (zagnieżdżony w imporcie), INVALID_HF_DOWNLOAD, DOWNLOAD_BUSY, DOWNLOAD_HTTP_<status>, INCOMPLETE_DOWNLOAD, OPERATION_NOT_FOUND, ACTIVE_MODEL_CANNOT_BE_DELETED, SHARDED_DELETE_REQUIRES_OPERATOR, INSTALLER_UNAVAILABLE, INSTALLATION_BUSY, INSTALLATION_FAILED, INSTALLATION_TIMEOUT. Message może zawierać dodatkowy kontekst; kod nie jest tekstem do lokalizacji przez substringy.

## Zmiana silnika/modelu i proces

1. UI wysyła pełny SwitchRequest. Core sprawdza silnik, model/pliki, parametry, instalację i tryb zarządzania przed zwalnianiem GPU.
2. Wyłączna dzierżawa czeka na generacje; ENGINE_BUSY nie zmienia wyboru.
3. SWITCHING: stare llama.cpp jest zatrzymywane; wszystkie modele Ollamy są zwalniane przez keep_alive=0, Core czeka na pustą `/api/ps`.
4. LOADING_MODEL: Core sprawdza zasoby i startuje jeden proces llama-server na 127.0.0.1. Nie uruchamia procesu na każde żądanie. Oczekuje do 180 s na /health oraz właściwy alias w /v1/models.
5. Atomowy zapis konfiguracji z backupem poprzedniej; publikacja nowej Selection dopiero po gotowości. READY potwierdza rzeczywisty wybór.
6. Błąd: RECOVERING, zatrzymanie nieudanego nowego procesu i próba przywrócenia poprzedniego modelu; przy niepowodzeniu bramka przechodzi do unavailable, a stan do ERROR. Nie ponawiaj automatycznie tasków/narzędzi.

Core jest jedynym właścicielem swojego dziecka llama-server. systemd nadzoruje Core, nie osobne llama-server. Zajęty port kończy start błędem; Core nie adoptuje ani nie zabija obcego procesu. Stop wysyła kontrolowane zakończenie, a po timeout używa wymuszonego zakończenia własnego dziecka. Monitor co 5 s wykrywa awarię/niezdrowy proces po 3 próbkach. Odzyskiwanie po awarii wymaga jawnego `/process restart`; nie ma nieskończonej pętli restartów ani replay generacji. Zewnętrzne klienty Ollamy i drugi proces Core są poza blokadą JVM — wdrożenie zakłada jednego właściciela inference.

## Streaming i reconnect

Windows nadal odbiera istniejące zdarzenia `/ws/jarvis`/Companion; brak osobnego parsera Ollamy i SSE. Core składa wieloczęściowe tool arguments według index/id, oddziela reasoning od widocznego content, wymaga finish_reason i `[DONE]`, odrzuca pustą lub reasoning-only finalną generację. Nie próbuje wydobywać tekstu z Harmony przez regex. Template/parser Harmony musi być prawidłowo obsługiwany przez konkretny zainstalowany llama-server; model VISION wskazany w specyfikacji nie jest uznawany za kompatybilny i nie ma workaroundu RAW. Zdarzenia SSE z jawnym id są deduplikowane; bez id nie zgaduje się duplikacji na podstawie fragmentów odpowiedzi. Powtórzenie słowa nie jest dowodem duplikatu.

Nowe rodziny w istniejącym journal `/ws/companion`:
- `inference.operation.updated`: payload {operationId,revision,status,selection,error}.
- `inference.download.updated`: payload DownloadOperation.
- `inference.installation.updated`: payload {operationId,status,error}.

Envelope nadal ma eventId, protocolVersion, timestamp, sequence, type, taskId (null dla zarządzania) i payload. Używaj dotychczasowych serverInstanceId, sequence, replay i reset_required. Zdarzenia trafiają tylko do właściciela operacji; pozostali administratorzy odświeżają /state. eventId/sequence deduplikują ramki; revision deduplikuje stan danej operacji. Na reconnect: pobierz snapshot Companion i replay zgodnie z istniejącym protokołem, następnie /inference/state, /operations i /installation; REST jest autorytatywny. Nie cofaj globalnego stanu ramką inference o starszej revision. Po zmianie serverInstanceId wyczyść cache revision i pobierz stan od nowa. GET /operations po restarcie nie obiecuje historii pobrań. Nigdy nie twórz nowego taska wskutek replay.

## Wymagania Windows

Ustawienia AI / Inference Engines: selector engine → GET /models; model → pełne /selection. Wyświetlaj status, błędy, parametry z parameterSchema, deklarowane/zweryfikowane możliwości oddzielnie, próbkę VRAM/RAM z observedAt i jednostkami, dostępne metryki generowania, instalację, pobieranie, import, restart i benchmark. Szybki selektor głównego okna korzysta z tego samego kontraktu. Nie mapuj modeli między silnikami po podobnej nazwie. Ścieżka importu jest ścieżką na serwerze w katalogu modeli, nie lokalnym plikiem Windows; upload dużych GGUF z Windows nie jest zaimplementowany. Nie pokazuj przycisku podmiany sterownika ani dowolnej komendy.

Zablokuj ponowny przycisk Apply podczas zmiany, ale zachowaj możliwość rozmów i czytelnego błędu ENGINE_BUSY. Pobieranie pokazuje rzeczywiste bytes/total; null = nieznane, nie zero. Anulowanie jest idempotentne dla terminalnej operacji. Restart nie ponawia poprzedniej wypowiedzi. TTFT i tokens/s są nullable; nie zastępuj braku pomiaru zerem.

Benchmark endpoint oznacza start jako WARM_RESIDENT, COLD_MODEL_LOAD lub RESIDENCY_UNVERIFIED według obserwacji /api/ps lub własnego procesu i raportuje ustalony prompt, konfigurację, tokeny backendu, TTFT, całkowity czas, tempo od pierwszego zdarzenia i próbki zasobów. `promptProcessingTokensPerSecond=null`; testy narzędzi/JSON/vision/równoległości mają NOT_RUN. To ograniczony pomiar, nie pełny test jakości ani cold-vs-warm. UI ma pokazać te ograniczenia; nie wyświetlać PASSED na podstawie samej deklaracji modelu.

## Źródła i zgodność wersji

Adapter i argv porównano z oficjalnym README przypiętego commita: https://github.com/ggml-org/llama.cpp/blob/074f1f3f730c1d17f34645d62c1859c74bedbdd8/tools/server/README.md . Nie oznacza to weryfikacji działającej binarki na produkcji. Skrypt zapisuje --version, --help, listę urządzeń, SHA binarki i installation.json; /health i /v1/models sprawdzane są przy każdym ładowaniu. API chat wymaga rzeczywistego testu po instalacji. Oficjalne build docs: https://github.com/ggml-org/llama.cpp/blob/074f1f3f730c1d17f34645d62c1859c74bedbdd8/docs/build.md . Instalacja CUDA: https://docs.nvidia.com/cuda/cuda-installation-guide-linux/ .
