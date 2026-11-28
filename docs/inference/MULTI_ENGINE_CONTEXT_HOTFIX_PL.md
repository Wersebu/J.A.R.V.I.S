# Core 2.24.0 — poprawka multi-engine i budżetu kontekstu (kontrakt 1.9.0)

Data: 2026-11-28. Zmiany dotyczą Core i dokumentacji współdzielonej. Windows UI pozostaje bez zmian. Nie wykonano wdrożenia na serwer produkcyjny.

## Routing, snapshot i przełączanie

CognitivePipelineExecutor przypina jeden immutable Selection przed pierwszym etapem. BrainCatalog korzysta z niego także przy wyborze logicznego Brain. Pamięć autonomiczna, decyzje pamięci i reranking przypinają konfigurację na całą operację, a Coding przenosi ją do executora. Profil Ollamy nie nadpisuje modelu przypiętego konfiguracją requestu. Tytuł korzysta z przypiętego silnika/modelu; przestarzała praca pozostaje odroczona, bez generowania zastępczego tytułu na innym silniku.

STALE_CONFIGURATION, EngineAccessGate, autoryzacja operacji i ochrona skutków ubocznych pozostają aktywne. Zmiana silnika czeka na dzierżawy, a błąd aktywacji uruchamia rollback. Stare zadania nie są automatycznie przypisywane do innego silnika. Odrębna blokada serializuje przełączenia bez trzymania monitora odczytu Selection podczas oczekiwania na zakończenie generacji.

Startup warmup pomija Ollamę, gdy aktywne jest llama.cpp. Odtworzenie i restart poprawnie zapisanej konfiguracji llama.cpp nie odczytuje /api/ps Ollamy. Pierwsze przejście z aktywnej Ollamy nadal wymaga bezpiecznego zwolnienia jej modelu. Brak potwierdzenia zwolnienia nie jest ignorowany.

Coding na llama.cpp używa Core Native Tool Runtime. Windows OpenCode pozostaje ścieżką Ollamy: otrzymuje przypięty aktywny model i dzierżawę ModelGateway obejmującą jego pracę. Nie przekazujemy localhost:8082 z Linuksa jako adresu dostępnego z Windows. Wyspecjalizowany opis obrazu używa aktywnego llama.cpp; żądanie wymuszenia CPU przy aktywnym modelu GPU zwraca CPU_AUXILIARY_UNAVAILABLE zamiast uruchamiać Ollamę. Zachowano istniejący, odrębny CPU embedding provider Ollamy; nie zastępujemy wektorów embeddingami modelu konwersacyjnego. Bez Ollamy opcjonalne embeddingi nadal mogą być niedostępne.

## Parametry i limity

Ostatnie zatwierdzone parametry każdego engine/model są zapisywane atomowo w pliku obok konfiguracji: inference-engine.json.models.json. Brak parametrów lub pusty obiekt oznacza przywrócenie zapisanych ustawień. Dla nieznanego GGUF zwracane jest HTTP 409 z code MODEL_CONFIGURATION_REQUIRED przed zatrzymaniem poprzedniego modelu. Niepuste parameters oznacza pełną nową konfigurację, zgodnie z dotychczasowym kontraktem PUT; pozwala usunąć opcjonalny mmproj, szablon lub kwantyzację cache przez ich pominięcie. Samo odkrycie pliku nie jest deklaracją optymalnej gotowości.

Walidatory i parameterSchema obsługują parallelSlots 1..16, reasoning on/off/auto, reasoningEffort low/medium/high oraz istniejące parametry mmproj, chatTemplate, Jinja, flash attention, K/V cache, context, GPU, batch i CPU. Polecenie jest listą argumentów ProcessBuilder, bez powłoki. --jinja jest przekazywane przed --chat-template-file. Pozostają ograniczenia ścieżek do katalogu modeli, kontrola GGUF i VRAM/RAM. GPU nadal wymaga jawnie zatwierdzonego gpuBudgetMiB i 2048 MiB rezerwy.

Dla contextSize=24576 i parallelSlots=3 limit jednego requestu wynosi najwyżej 8192, a nie 24576. /props dostarcza n_ctx i total_slots; niezgodność liczby slotów przerywa aktywację. Niedostępność metadanych jest jawnie oznaczona jako CONFIGURED_FALLBACK. ModelGateway i limit łącznego kontekstu używają parametrów aktywnego silnika. Zachowano priorytety, kolejkę i wyłączną rezydencję. Zarezerwowana odpowiedź obejmuje także tokeny reasoning.

Przykłady ustawień dla przekazanego hosta: SERVER/config/llamacpp-production.example.yml i llamacpp-production-selection.json.template. Model directory /mnt/dysk8tb/llamacpp-models, port 8082, executable /opt/jarvis/engines/llamacpp/current/bin/llama-server. Szablon JSON wymaga zastąpienia gpuBudgetMiB rzeczywiście zatwierdzoną liczbą; nie jest gotowym pomiarem VRAM ani automatycznie zastosowaną konfiguracją.

## Kontekst i wyniki narzędzi

ModelContextBudget jest wspólną metryką szacowania UTF-8 dla wiadomości, definicji, argumentów tool_calls, obrazów i rezerwy protokołu. To heurystyka, nie tokenizer backendu. Oszacowanie zajęcia przed wywołaniem i rzeczywiste prompt_tokens po wywołaniu mają różne pola i etykiety. Nie deklarujemy dokładnego zajęcia KV na podstawie długości tekstu.

Historia rozmowy jest ograniczana według bieżącego budżetu pojedynczego requestu, z zachowaniem oryginalnych wiadomości w SQLite. Tożsamość i polityki bezpieczeństwa nie są skracane przez mechanizm wyników narzędzi. Bardzo duży obowiązkowy prompt może nadal otrzymać jawny błąd, zamiast zostać po cichu obcięty.

Przed prepare i transportem pętla deterministycznie wylicza miejsce na wyniki narzędzi, skraca stare i duże wyniki oraz usuwa dokładne duplikaty instrukcji. Nie wykonuje dodatkowej generacji LLM do kompresji. ToolResultCompression zachowuje prawidłowy JSON, success/error, krótkie identyfikatory i adresy źródeł. Krótkie listy (np. pięć maili) zachowują wszystkie pozycje, skracając długie treści. Duże listy i teksty mają jawne oznaczenie częściowości oraz outputId do pełnych danych, dostępnych wyłącznie właścicielowi.

Oryginalne wyniki są archiwizowane przed kompresją; kolejne kompresje używają tego samego oryginalnego outputId. output__read obsługuje stronicowanie znakowe offsetChars/maxChars dla długich linii JSON, obok starego startLine/limit. Wynik strony jest ograniczony do 6000 znaków. output__grep pozostaje dostępny. Odczyt własnego archiwum jest wewnętrznym, tylko do odczytu wymaganiem wybranej operacji; nie rozszerza dostępu do danych innego właściciela.

Czytnik WWW usuwa sekcje nawigacji i zbędne powtarzające się linie oraz archiwizuje pełny znormalizowany odczyt przed ograniczeniem widoku. Obecne limity pobierania, walidacja DNS/SSRF, zakaz downgrade HTTPS i kontrola przekierowań pozostają aktywne. Nie zwiększono context window ani limitu pobierania stron jako obejścia problemu.

Jeżeli kompresja nie wystarcza, zapisujemy checkpoint z odnośnikiem do archiwum i zachowujemy kompletne grupy assistant tool_calls / tool results wraz z identyfikatorami. Na końcu możliwa jest jedna ograniczona synteza bez narzędzi, oparta na dostępnych dowodach i jawnie częściowa. Nie ponawia się skutków ubocznych ani identycznego zbyt dużego promptu. SemanticOrchestrator nie traktuje przepełnienia lub STALE_CONFIGURATION jako błędu JSON do wielokrotnej naprawy.

## Pogoda, waluty i zwykła rozmowa

weather__get korzysta z geocoding Open-Meteo i prognozy w JSON: current/hourly/daily, data lub tomorrow, 1..7 dni, 1..24 godziny, temperatura/odczuwalna/opady/wiatr, lokalizacja, strefa, jednostki, źródło i czas pobrania. Brak lokalizacji musi być rozwiązany z historii albo doprecyzowany; nie zgadujemy jej w HTTP kliencie. Odpowiedź opisuje model pogodowy, nie bezpośredni pomiar stacji.

exchange__rate zwraca ostatni opublikowany kurs średni NBP waluty do PLN, wraz z effectiveDate, numerem tabeli i źródłem. To kurs referencyjny, nie kurs transakcyjny ani kwotowanie live. Inne pary i specyficzne kwotowania nadal mogą wymagać SearXNG. Klient informacji przyjmuje tylko stałe originy HTTPS, blokuje przekierowania i ogranicza czas oraz rozmiar JSON.

SemanticOrchestrator nadal podejmuje decyzję znaczeniową na podstawie bieżącego żądania, referencji i katalogu rodzin. Proste połączone odczyty pogody, kursu i maili mają pozostać TOOLS w Main Conversation. Dedykowane małe definicje pogody/walut mogą zostać użyte bez osobnego generowania planu; katalog wszystkich dużych definicji nie jest dodawany do pierwszej decyzji. Jawne długie lub niezależne zadania nadal mogą być delegowane. Krótkie kompletne powitanie omija decyzję semantyczną i duży katalog polityk narzędzi, zachowując główny prompt osobowości/pamięci.

Zwykły odczyt informacyjny nie otrzymuje raportu plików projektu, testów ani liczników Coding Agenta. Szczegółowe liczniki pozostają w diagnostyce.

## SSE i zapis odpowiedzi

Błąd po rozpoczęciu SSE jest końcowym event: ERROR z istniejącą strukturą CognitiveEvent i code INFERENCE_FAILED, po czym stream kończy się normalnie. Nie przekazujemy wyjątku do REST convertera po rozpoczęciu odpowiedzi text/event-stream. Błędny token jest odrzucany przed utworzeniem SSE. Nie dodajemy stack trace ani prywatnych danych backendu do ramki. Pipeline nadal nie zapisuje błędu jako poprawnej odpowiedzi AI; częściowe strumieniowane odpowiedzi błędnych pipeline mają status FAILED.

## Audyt zależności

Generowanie rozmowy, semantyka, BrainRouter, Task Agents, tytuł, pamięć/decyzje/reranking, research/planning/coding, companion i vision korzystają z aktywnego wyboru albo jego zachowanego snapshotu. Bezpośrednia zależność od OllamaProvider pozostaje w adapterze i kontrolowanym dispatcherze legacy CPU vision. Stałe gpt-oss:20b pozostają jako wartości bootstrap/legacy przy braku InferenceControl, alias nazwy modelu i sztuczne przykłady EventController; nie nadpisują aktywnej konfiguracji requestu. Wyspecjalizowany istniejący TopkiMC i embeddingi zachowują osobne kontrakty, opisane w bazowym kontrakcie 1.9.0.

## Weryfikacja i ograniczenia

Osobne testy HTTP adaptera sprawdzają streaming, brak retry niekompletnego SSE, tool roundtrip/schema/ID, reasoning oddzielony od content oraz przesłanie obrazu. Pełny Spring Cognitive Pipeline sprawdza obie kolejności przełączeń, aktywną semantykę, proste powitanie, TaskExecutionContext i wszystkie osiem scenariuszy informacyjnych na obu silnikach przy 8192 tokenów na slot. Dane zewnętrzne i odpowiedzi modeli w tych testach są kontrolowanymi fixture, a nie produkcyjnym benchmarkiem.

Brak dostępu do produkcyjnej karty RTX 4060 Ti i podanych GGUF w tej sesji. Wyniki operatora 44,25 tok/s/agenta, 128,84 tok/s łącznie i TTFT 197 ms nie zostały tu ponownie zmierzone. OCR i jakość długiego kontekstu NIE SĄ ZWERYFIKOWANE. Czasy fixture służą diagnostyce regresji, nie ocenie szybkości lub jakości rzeczywistego modelu.

Dokładne wyniki zestawów, SHA-256 JAR i tabela scenariuszy znajdują się w metadanych wydania dołączonych do tego raportu.

## Źródła implementacji API

- https://open-meteo.com/en/docs
- https://open-meteo.com/en/docs/geocoding-api
- https://api.nbp.pl/
- https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md

## Wynik końcowej weryfikacji

1142 testy w najnowszych raportach modułów: 1139 zaliczonych, 3 świadomie pominięte testy live, 0 niepowodzeń, 0 błędów. Pełny reactor: multi-engine-context-verified.log — BUILD SUCCESS. Następny przebieg regresji konfiguracji i pakowania: multi-engine-context-artifact.log — BUILD SUCCESS. Schemat draft 2020-12: 14 poprawnych przykładów, 3 niepoprawne odrzucone, kopie SERVER/SHARED zgodne.

| Silnik | Scenariusz | Wywołania modelu | Maks. szacowany kontekst | Czas fixture ms |
|---|---|---:|---:|---:|
| ollama | Jaka pogoda w Warszawie? | 2 | 2838 | 341 |
| ollama | Jaka będzie pogoda jutro? | 2 | 3050 | 96 |
| ollama | Jaki jest kurs dolara? | 2 | 2835 | 129 |
| ollama | Sprawdź 5 ostatnich maili. | 3 | 4150 | 130 |
| ollama | Sprawdź pogodę w Warszawie, kurs dolara i ostatnie maile, a następnie przygotuj jedną odpowiedź. | 3 | 4962 | 122 |
| ollama | Odczytaj bardzo dużą stronę internetową. | 3 | 2972 | 199 |
| ollama | Wykonaj wieloetapowy research porównujący trzy źródła. | 4 | 3385 | 306 |
| ollama | Kontynuuj długą rozmowę z wieloma wynikami narzędzi. | 13 | 5712 | 1405 |
| llamacpp | Jaka pogoda w Warszawie? | 2 | 2838 | 86 |
| llamacpp | Jaka będzie pogoda jutro? | 2 | 3050 | 74 |
| llamacpp | Jaki jest kurs dolara? | 2 | 2836 | 96 |
| llamacpp | Sprawdź 5 ostatnich maili. | 3 | 4150 | 91 |
| llamacpp | Sprawdź pogodę w Warszawie, kurs dolara i ostatnie maile, a następnie przygotuj jedną odpowiedź. | 3 | 5200 | 100 |
| llamacpp | Odczytaj bardzo dużą stronę internetową. | 3 | 2972 | 166 |
| llamacpp | Wykonaj wieloetapowy research porównujący trzy źródła. | 4 | 3385 | 273 |
| llamacpp | Kontynuuj długą rozmowę z wieloma wynikami narzędzi. | 13 | 6843 | 1159 |

Wszystkie scenariusze miały limit 8192 na slot; wartości tabeli nie są pomiarami prawdziwego modelu. Długa rozmowa obejmowała 40 wcześniejszych dużych wiadomości i wiele wyników narzędzi.

Artefakt: SHARED/releases/2026-11-28-multi-engine-context/jarvis-core-2.24.0-multi-engine-context.jar, 42930252 bajtów.

SHA-256: `6b2feb063fff351e7962f86dd72c553e3d46aa1833efd5b9d719d10a707bfaee`. Sprawdzono manifest produkcyjnego entrypointu i obecność nowych klas w bibliotekach Spring Boot. Dokument dla Claude: SHARED/INFERENCE_CONTEXT_HOTFIX_FOR_CLAUDE.md. Metadane: SHARED/INFERENCE_CONTEXT_HOTFIX_METADATA.json.
