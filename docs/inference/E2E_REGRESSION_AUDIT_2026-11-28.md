# Audyt regresji end-to-end — 28 listopada 2026

## Wynik i granice weryfikacji

Wdrożono poprawki Core i zbudowano nowy JAR. Regresja Maven: 1150 testów, 1146 wykonanych bez błędów, 4 pominięte testy opt-in, 0 failures, 0 errors. Wynik Maven nie oznacza potwierdzenia pełnej akceptacji produkcyjnej.

Rzeczywisty lokalny przepływ REST/SSE → pipeline → router → gateway → Ollama gpt-oss:20b → narzędzia → agregacja → SQLite → terminalne zdarzenia: 4 workery, stan korzenia PARTIALLY_COMPLETED. Model pracował z kontekstem 8192. Workery korzystały ze wspólnego gateway i tego samego modelu. Brak mockowanych providerów/usług w RealIngressAuditTest.

Pełne fakty BTC/CSS i historia sześciu miesięcy: NOT_VERIFIED. Lokalny SearXNG na porcie 8888 był niedostępny. llama.cpp na porcie 8082 nie działało, a lokalna karta ma 12 GiB, zamiast docelowych 16 GiB. Produkcyjnego przełączenia Ollama → llama.cpp → Ollama oraz faktycznego renderowania Windows UI nie potwierdzono. Nie wolno oznaczać ich PASSED.

## Przyczyny i poprawki

- Router potrafił zaakceptować pierwszy wykonywalny tool call jako cały zakres złożonego pytania. Decyzja zakresu jest teraz oddzielona od wykonania, a niezależne cele semantyczne tworzą rzeczywiste workery.
- Treść kanału content mogła być argumentami oferowanego narzędzia. Walidacja względem schematów powstrzymuje taki wynik przed zapisem i końcową publikacją. Native tool calls nigdy nie są odpowiedzią. JSON zamówiony przez użytkownika pozostaje dozwolony.
- Fragmenty tool_calls Ollamy zastępowały poprzednie wywołania. Teraz są łączone według id/index, z kontrolą konfliktów. Reasoning, content i operacje pozostają osobnymi kanałami.
- Agregacja mogła zacząć się przed zamknięciem rejestracji workerów lub zakończyć jako sukces po awarii syntezy. Usunięto obie sytuacje. Awaria nie powtarza narzędzi; zachowuje wyniki częściowe i opublikowany prefiks.
- Rzeczywisty model zwracał w syntezie action=ANSWER zamiast DIRECT. Obsługiwane są oba warianty odpowiedzi, ale TOOLS/DELEGATE pozostają niedozwolone. ANSWER jest publikowane dopiero po walidacji całej koperty, jednorazowo, bez symulowania streamingu.
- Do aktywności dodano rozmowę interaktywną i koordynatora. Zakończenie workerów nie oznacza IDLE podczas syntezy.
- „siemanko” i pytanie o tożsamość omijają zbędne planowanie. Lekkie profile, w tym przetwarzanie wyników, używają LOW, chyba że konfiguracja jawnie nadpisuje profil.
- Niedostępny backend wyszukiwania zatrzymuje workera wyszukującego, zamiast zużywać kolejne tury na różne zapytania do tego samego niedostępnego źródła. date=today jest akceptowane dla bieżącej pogody.

## Powitanie i tożsamość

| Żądanie | Czas ingress [s] | Wywołania głównej odpowiedzi | Input backendu | Output backendu | Odpowiedź |
|---|---:|---:|---:|---:|---|
| siemanko | 2.018 | 1 | 280 | 31 | siemanko |
| kim jesteś? | 6.373 | 1 | 1673 | 83 | Jestem J.A.R.V.I.S., osobistym asystentem wirtualnym stworzonym, by pomagać Ci w codziennych zadaniach i odpowiadać na pytania. |

To tokeny głównej odpowiedzi przechwycone w rzeczywistym SSE. Osobne asynchroniczne generowanie tytułów nie jest dodawane do aktualnego zajęcia kontekstu ani ukrywane jako część jednego wywołania. Wyniki zależą od tożsamości i pamięci użytkownika; izolowany test nie odtwarza całej produkcyjnej historii.

## Pojedyncze narzędzie

Czas ingress: 11.933 s. Końcowa treść:

W Warszawie dziś (27 listopada 2026) jest około **3,2 °C**. Temperatura odczuwalna to **-1,2 °C**, opady małe – 0,1 mm, a wiatr z południowo‑zachodu w prędkości ~18,5 km/h. Pogoda jest przymroziona i umiarkowanie wietrza.

## Cztery niezależne zadania i dowód równoległości

Rozpoczęcie żądania: 2026-11-28 03:10:54.447405 UTC. Zakończenie obserwacji: 2026-11-28 03:11:56.356123900 UTC. Łącznie 61.909 s. Maksimum aktywnych workerów: 4; maksimum jednocześnie zajętych slotów gateway: 2. Pełne próbkowanie co 500 ms znajduje się w JSON.

Nakładanie się okresów pracy poniżej potwierdza współbieżne logiczne workery. Slot gateway nie jest dowodem równoczesnej pracy jąder GPU ani potwierdzeniem konfiguracji wewnętrznych slotów Ollamy.

| Task / faza | Początek UTC | Koniec UTC | Wywołania | Input skumulowany | Output skumulowany | Input ostatniego wywołania |
|---|---|---|---:|---:|---:|---:|
| 0a886695-c554-42d8-8c30-16ecb65183b6 / WORKER | 2026-11-28 03:11:18.905663100 UTC | 2026-11-28 03:11:35.419625 UTC | 1 | 1369 | 79 | 1369 |
| 463469b9-f9b5-4c4c-aaf2-3011e03ab20b / WORKER | 2026-11-28 03:11:18.987044400 UTC | 2026-11-28 03:11:32.101957 UTC | 1 | 1321 | 59 | 1321 |
| b00183de-fbce-4a5e-93d4-0d1d4e0c4e96 / WORKER | 2026-11-28 03:11:19.069335900 UTC | 2026-11-28 03:11:37.740975300 UTC | 2 | 2105 | 95 | 1148 |
| 7a58e59d-c2c8-488d-b039-a63a169b7e24 / WORKER | 2026-11-28 03:11:19.188939500 UTC | 2026-11-28 03:11:42.015640800 UTC | 2 | 2486 | 159 | 1461 |
| a4990ef9-0a30-4567-a39a-bd55034d3f7c / AGGREGATION | 2026-11-28 03:11:42.198686600 UTC | 2026-11-28 03:11:56.160874700 UTC | 1 | 1559 | 303 | 1559 |

Zmierzone workery + agregacja: 7 wywołań, 8840 input tokens, 695 output tokens. To nie jest pełny koszt wszystkich operacji aplikacji: początkowa decyzja routera, jej ewentualne naprawy, powitanie równoległe oraz tytuły są osobnymi wywołaniami. Telemetria routingu i surowy log wskazują ich zakres; nie dopisano niezmierzonych tokenów do tej sumy.

Stan po zakończeniu: `{"busy": false, "executionState": "IDLE", "activeWorkerCount": 0, "activeCoordinatorCount": 0, "activeInteractiveRequests": 0, "gatewaySlotsInUse": 1}`.

## Końcowa odpowiedź dla wielozadaniowego pytania

**Pogoda w Warszawie (dzisiejsza)**
- Temperatura: 3,2 °C
- Odczuwalna temperatura: –1,2 °C
- Opady: 0,1 mm
- Kod pogodowy: 51 (pochmurne)
- Wiatr: 18,5 km/h z kierunku 243°
(Źródło: Open‑Meteo, wynik potwierdzony.)

**Kurs dolara (USD/PLN)**
Najświeższa wartość według NBP to **4,0754 PLN** (data publikacji: 27.11.2026).
(Źródło: API NBP, wynik potwierdzony.)

**Cena Bitcoina**
Informacja nie została uzyskana – wyszukiwanie w sieci zakończyło się błędem.

**Wyniki akcji CSS (NASDAQ)**
Aktualne notowania oraz 6‑miesięczna historia kursów również nie zostały pobrane, ponieważ zapytanie do źródła internetowego zakończyło się błędem.

## Rozmowa podczas pracy agentów

{"response": "Cześć!"}

## Anulowanie

{"taskId": "ee8310cb-fa61-4ba0-9bea-eb169bd5a422", "role": "RESEARCH", "state": "CANCELLED", "priority": 50, "progress": null, "createdAt": 1795835516.3835435, "updatedAt": 1795835516.588891, "result": null, "error": null, "conversationId": "cancel-audit", "executionConversationId": "companion-ee8310cb-fa61-4ba0-9bea-eb169bd5a422"}

## Zdarzenia do UI, zapis i reconnect

Rzeczywisty ingress SSE zapisano w `uiEvents`. Zdarzenia workerów i agregacji zapisano w trwałym dzienniku Companion, a następnie odczytano przez replay. Typy i liczby:

```json
{
  "agent.title.started": 5,
  "agent.title.completed": 4,
  "conversation.title.updated": 6,
  "orchestration.decided": 3,
  "tool.execution.started": 1,
  "tool.execution.completed": 1,
  "orchestration.retrying": 1,
  "task.created": 5,
  "task.plan.created": 1,
  "task.plan.step.updated": 8,
  "task.started": 4,
  "agent.inference.started": 6,
  "agent.reasoning.started": 4,
  "agent.inference.usage": 6,
  "agent.reasoning.finished": 4,
  "agent.tool.started": 4,
  "agent.tool.completed": 2,
  "agent.tool.failed": 2,
  "task.failed": 2,
  "task.completed": 2,
  "assistant.message.generation.started": 1,
  "assistant.generation.metrics": 1,
  "assistant.message.delta": 1,
  "assistant.message.generation.finished": 1,
  "assistant.message.created": 1,
  "task.plan.updated": 1,
  "task.partially_completed": 1
}
```

Payloady zachowują request/conversation/task/agent IDs, sekwencję, messageId i rozdzielenie rozmowy logicznej od wykonawczej. Granice reasoning są rzeczywistymi zdarzeniami providera. Prywatnych tokenów reasoning nie dodano do odpowiedzi ani do publicznego journalu. Samo wyświetlanie na żywo w Windows i rzeczywiste rozłączenie/ponowne połączenie Windows: NOT_VERIFIED. Testy replay/socket oraz odtworzenia przerwanej agregacji obejmują protokół i SQLite; nie są testem docelowego UI.

## llama.cpp i konfiguracja modelu

Kandydat profilu: `SERVER/config/llamacpp-production-selection.json`: gpuLayers=99, contextSize=24576, parallelSlots=3, 8192/slot, cache K/V=q8_0, flashAttention=on, mmproj i chatTemplate, jinja=true, reasoning=on, effort=low. gpuBudgetMiB=14000 jest budżetem kandydującym, nie zmierzonym zużyciem modelu/KV/projektora. Próg wymaganej wolnej VRAM wynosi 16048 MiB (14000+2048). Lokalny sprzęt 12 GiB nie spełnia tego progu; nie obchodzono zabezpieczeń.

Odczyt kodu Windows potwierdził ścieżkę InferenceQuickSelector → skonfiguruj model → ParameterEditor, z obsługą MODEL_CONFIGURATION_REQUIRED w InferenceViewModel. Backend udostępnia parameterSchema oraz PUT configuration / POST selection. Interaktywna ścieżka UI i aktywacja modelu produkcyjnego: NOT_VERIFIED. Do zatwierdzenia potrzebny jest działający llama-server, docelowe pliki GGUF/mmproj/template, właściwa karta, odczyt nvidia-smi i rzeczywisty test alokacji.

## Restart Core

Osobna próba uruchamia dwukrotnie rzeczywisty JAR z izolowanym SQLite i konfiguracją, wykonuje bootstrap/login przez REST oraz porównuje configurationId/engine/model/parameters. Szczegóły i hash testowanego artefaktu znajdują się w restart-audit.json. Restart z aktywną konfiguracją produkcyjnego llama.cpp pozostaje NOT_VERIFIED.

## Macierz pozostałych sprawdzeń

- Odrzucenie zgłoszonego JSON argumentów i zachowanie JSON żądanego przez użytkownika: testy FinalAnswerGuard; wieloramkowe tool calls: test transportowy Ollamy.
- Duże strony, długa historia, mieszane narzędzia i context 8192 dla obu adapterów: InformationContextPipelineTest z kontrolowanymi serwerami/providermi. To PROTOCOL_FIXTURE, nie rzeczywiste llama.cpp.
- Pełny zestaw produkcyjnych zapytań mailowych i researchu: NOT_VERIFIED bez integracji i działających źródeł. Nie podano fikcyjnych wyników maili ani historii giełdowej.
- Jakość faktów: potwierdzono zwroty dedykowanych API i jawne braki źródeł. Sformułowania modelu, interpretacja kodów pogodowych i niejednoznacznego CSS wymagają oceny; pełnej poprawności merytorycznej rynku nie zatwierdzono.
- Zachowano zabezpieczenia VRAM, kontrolę dostępu, izolację właścicieli wyników, ograniczenia narzędzi i ochronę przed ponawianiem skutków ubocznych.

## Artefakty i odtworzenie

JAR: `SHARED/releases/2026-11-28-e2e-audit/jarvis-core-2.24.0-e2e-audit.jar`. SHA-256 jest w SHA256SUMS.txt. build-verification.json zawiera manifest, rozmiar i wyniki testów. `real-ingress-audit.json` zawiera pełne odpowiedzi, zdarzenia, snapshoty i telemetrię. Skrypty testowe: RealIngressAuditTest oraz test-tmp/restart-audit.py. Uzupełnienie kontraktu dla Claude: SHARED/E2E_REGRESSION_CONTRACT_FOR_CLAUDE.md.

## Osobne wywołania pomocnicze

Katalogi rzeczywistych wywołań native według profilu (cała próba, również tytuły i routing): `{"conversation-title": 5, "main-conversation": 4, "null": 2, "agent-execution": 6, "tool-result-processing": 2}`. Zwykłe wywołania HTTP_CHAT: 2. To liczby rozpoczętych wywołań; szczegóły zakończeń są w logu.

Proste, niezależne workery informacyjne nie dostają companion__submit, więc nie mnożą pomocników po awarii źródła. Pozostałe zaplanowane zadania zachowują istniejącą obsługę dynamicznych pomocników. Brak Windows Bridge jest oznaczony sourceUnavailable i kończy odpowiedni worker bez kolejnych prób przeglądarki.

## Otwarte ustalenia jakościowe

W rzeczywistej odpowiedzi model dopisał NASDAQ przy CSS mimo nieudanego pobrania danych. To niepotwierdzona interpretacja i błąd jakości, nie wynik narzędzia. Nie zatwierdzono identyfikacji spółki ani giełdy; potrzebne są jednoznaczny ticker/nazwa oraz działające źródło. Opis słowny kodu pogodowego również nie został dostarczony przez narzędzie — jego interpretacja przez model nie jest potwierdzonym odczytem API. Zachowano dosłowną odpowiedź w raporcie, aby nie ukrywać tych ograniczeń. Naprawy kanałów, routingu i lifecycle nie stanowią gwarancji pełnej wierności merytorycznej generowanego tekstu.

## Zakres próby restartu finalnego JAR

Potwierdzono dwa starty finalnego artefaktu z wcześniej zapisanym, izolowanym plikiem wyboru Ollamy oraz odtworzenie tego samego configurationId, modelu i parametrów. Konfiguracja wejściowa była przygotowana w pliku testowym (SEEDED_SAVED_CONFIGURATION); ta próba nie weryfikuje mutacji wyboru przez API. Oddzielna próba PUT wyboru finalnego artefaktu przekroczyła 60-sekundowy timeout klienta podczas przeładowywania rzeczywistego modelu; oznaczono ją NOT_VERIFIED, bez obchodzenia zabezpieczeń i bez zwiększania limitu kontekstu. Wyniki wcześniejszych udanych prób API nie zastępują tego ustalenia.
