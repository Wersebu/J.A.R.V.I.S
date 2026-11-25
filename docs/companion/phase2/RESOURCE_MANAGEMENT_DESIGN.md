# Projekt zarządzania zasobami i telemetrii Core + Windows

> Implementation follow-up: PHASE2_IMPLEMENTATION.md and PHASE2_CORE_COORDINATION.md. The latest operator-qualified physical target is 24576; earlier numbers below are historical design/audit evidence.


2026-11-25, projekt; niczego nie wdrożono. Audyt bazuje na `a0cc3c7`.

## Stan sprzętu i granice wiedzy

Od operatora: RTX 4060 Ti 16380 MiB, użyte 14889, wolne 1491; Ollama 13890, voice 984 MiB; RAM dostępny 116 GiB; model 22% CPU/78% GPU. Różnice sum obejmują inne alokacje i czas próbkowania. Nie dodawać ponownie Whispera do całkowitego used GPU. Te dane nie wykazują zapasu na kolejny KV slot. Dużo RAM nie oznacza wolnego VRAM, a offload może zwiększać TTFT i zmniejszać tok/s. Nie wnioskować wydajności z wolnego Java Heap.

## Kolektor i model zasobów

Osobny nieblokujący collector co 1–2 s: NVML (preferowane) lub `nvidia-smi` z timeoutem, per GPU UUID total/used/free/utilization oraz procesy PID/VRAM; `/proc/meminfo` MemAvailable, swap; limity cgroup memory.current/max/events i pressure CPU/memory jeśli dostępne. `/api/ps` model/digest/size/size_vram/context_length, `/api/version`; `ollama ps` jako dodatkowy zapis offload CPU/GPU. Size/size_vram są obserwacją rezydencji modelu, nie dokładnym licznikiem KV i nie procentem warstw offload. Procent `PROCESSOR` i liczba offloaded layers z logów opisują offload; bez danych pole null.

Każda próbka ma source, observedAt, ageMs, status AVAILABLE/STALE/UNAVAILABLE i units. Brak dostępu nie znaczy 0 użycia. Java heap pozostaje osobną metryką JVM. Collector nie blokuje monitora gateway i nie wywołuje nvidia-smi pod każdym acquire. Logi runnera/PID służą potwierdzaniu przeładowań; przejściowe /ps, load_duration i zmiany expiry to tylko wskazówki.

Koszt admission opiera się na zatwierdzonej tabeli `(endpoint, digest, physicalCtx, KV dtype, slots, offload, Whisper state)` zmierzonej benchmarkiem. Wagi modelu, KV i workspace oddzielone; nie mnożyć wag przez liczbę zapytań jednego modelu. Nie zakładać, że każdy przyjęty request dodatkowo alokuje pełny KV: runtime może prealokować sloty. Plan pamięci dla nowych modeli/runnerów musi uwzględniać pełną zmianę rezydencji. Brak tabeli → konserwatywny limit 1, nie ekstrapolacja z heap.

## Admission i degradacja

1. Początkowo `effectiveConcurrency=1`, dopóki operator nie zakwalifikuje fizycznego kontekstu i liczby slotów Ollamy. Skonfigurowana concurrency jest sufitem, nie gwarancją równoległości.
2. Limit efektywny = min(configured ceiling, measured safe capacity, verified runtime slot capacity). Nie odkrywać bezpiecznej capacity przez automatyczne podnoszenie obciążenia w produkcji.
3. Przy >=2 zarezerwować jeden slot dla foreground; background zajmuje najwyżej N−1. Przy 1 rezerwa fizyczna niemożliwa — następny foreground ma pierwszeństwo, obecne generowanie jest niepreemptowalne. Ograniczyć num_predict/deadline opcjonalnych operacji; odłożyć dreaming i tytuły.
4. Zatwierdzone progi startowe do kalibracji: zapas GPU 2048 MiB, MemAvailable 8 GiB (z uwzględnieniem cgroup), freshness <=5 s. Obecne 1491 MiB nie przechodzi takiego kryterium podniesienia concurrency. Progi same nie świadczą o koszcie nowego slotu.
5. Spadek zapasu, stale telemetry, OOM, powtarzalny timeout albo wzrost TTFT poza ustalony SLO → zmniejszyć effective limit i wstrzymać nowe opcjonalne/tła. Nie odbierać lease działającym requestom ani nie zwalniać go zanim HTTP faktycznie się zakończy. Istniejące aktywne mogą chwilowo przekraczać nowy limit. OOM otwiera circuit breaker, brak automatycznej pętli retry generowania z narzędziami.
6. Powrót tylko po np. 60 s stabilnych próbek, do uprzednio zatwierdzonej capacity; histereza. Nie zmieniać automatycznie num_ctx, KV dtype, offload, modelu ani ustawień usługi Ollama. Nie ubijać Whispera. Restart/unload wymaga osobnego działania operatora.
7. Bounded kolejki per klasa/owner i łączna; deadline od enqueuedAt, anulowanie i wygaszanie bez utraty owner context; kolejność priorytet + FIFO + kontrolowane aging bez odebrania rezerwy rozmowie. Backpressure z czytelnym reason i retry-after tylko dla bezpiecznego admission. Task slots i inference slots są oddzielne.

Wszystkie endpointy generowania, embedding, vision, moderation, probes i warmup powinny podlegać jednej polityce zasobów (z odrębnymi klasami). Samo spięcie zwykłego provider nie daje globalnej ochrony GPU. Kilka procesów Core / bezpośredni klient Ollamy omija lokalny limit — albo jeden właściciel admission, albo współdzielona koordynacja; do tego czasu telemetria oznacza zakres process-local.

## Propozycja kontraktu z Windows (do uzgodnienia z Claude)

Obecne `activeProcesses` liczy wszystkie nieterminalne zadania, także QUEUED/CANCEL_REQUESTED, **nie inferencje**. Nie zmieniać znaczenia istniejącego pola bez wersjonowania. Dodać capability `inferenceTelemetry`, opcjonalny snapshot i event `diagnostics.inference.snapshot` do następnej wersji kontraktu; zachować sequence/serverInstanceId/replay/reset i ignorowanie nieznanych pól/eventów przez stare klienty.

```json
{
  "scope": "owner",
  "observedAt": "2026-11-25T12:00:00Z",
  "tasks": {"running": 1, "queued": 2, "cancelRequested": 0},
  "inference": {
    "active": 1, "waiting": 1, "configuredLimit": 2, "effectiveLimit": 1,
    "runtimeParallelism": null, "reason": "UNVERIFIED_RUNTIME_CAPACITY",
    "requests": [{
      "inferenceId": "example", "requestId": "example", "taskId": null,
      "conversationId": "example", "agentId": null,
      "operation": "MAIN_CONVERSATION", "profileId": "main-conversation",
      "model": "gpt-oss:20b", "phase": "WAITING_GATEWAY", "queueWaitMs": 120,
      "requestedContext": 262144, "modelMaxContext": 131072,
      "physicalRunnerContext": 16384, "observedRunnerContext": null
    }]
  },
  "resources": {"status": "UNAVAILABLE", "source": null, "gpuFreeBytes": null}
}
```

To przykład schematu, nie pomiar. Fazy: QUEUED_TASK, PREPARING, WAITING_GATEWAY, HTTP_SUBMITTED, WAITING_RUNTIME (tylko jeśli zmierzone), PREFILL, REASONING, GENERATING, TOOL_IO, COMPLETED/FAILED/CANCELLED. Bez sygnału runtime nie fabrykować fazy PREFILL ani runtime queue time. Pola czasu osobne: taskQueueWait, gatewayQueueWait, HTTP TTFT firstAny/firstVisible, runtimeQueueWait nullable. Każda runda ma własny inferenceId, zachowuje taskId i conversationId. Helper synchroniczny ma agentId i parentRequestId; brak taskId pozostaje null, nie wymyślać zadania.

Owner snapshot nie ujawnia requestów innych użytkowników; globalne obciążenie może być pokazane jako zagregowane liczniki bez identyfikatorów. Szczegóły sprzętu i procesów tylko uprawnionym administratorom. Brak promptów, wyników narzędzi i reasoning w diagnostyce. „Aktywne zadania” i „aktywne inferencje” to osobne wskaźniki Overlay; zadanie może czekać na I/O bez inferencji.

## Pliki, testy i ryzyka

Nowe ResourceSnapshot/ResourceMonitor/AdmissionPolicy w common+core/ollama; rozszerzyć OllamaRequestCoordinator i provider lifecycle, spięcie moderation/probes/embedding; api Companion state/socket/controller i schematy `docs/companion`; Windows po stronie Claude: DTO, BackendEventType, snapshot/reconnect i Overlay. Trzymać osobno plan konfiguracji i obserwację sprzętową.

Testy: dane NVML bez GPU, stale/timeout/wiele GPU/cgroup; brak podwójnego liczenia voice; spadek limitu poniżej active; release dopiero po zakończeniu transportu; circuit breaker bez duplicate side effects; starvation/rezerwa/kolejki; izolacja tenantów; snapshot i reconnect starszego klienta; TTFT SLO przy obciążeniu i Whisperze. Ryzyka: koszt próbkowania, niepełne runtime metrics, presja RAM/PCIe przy offload, inne procesy GPU i zewnętrzne żądania. Wzrost concurrency może obniżyć prędkość pojedynczej rozmowy nawet przy wyższym aggregate tok/s.
