# Plan benchmarku Ollama 0.33.2 i pełnego Core

2026-11-25. Wykonuje operator na Ubuntu produkcyjnym w uzgodnionym oknie. Skrypt `benchmark_inference.py` wymaga Python >=3.10, wyłącznie standard library; opcjonalnie nvidia-smi, ollama CLI, ps i /proc. Nie zmienia systemd, zmiennych usługi, konfiguracji Core, wag ani modeli; nie wykonuje unload/restart. Żądania z różnym num_ctx mogą jednak zmienić runner i opóźnić obsługę użytkowników. Nie uruchomiono tego obciążenia na produkcji.

## Przygotowanie i niezależność pomiarów

Zapisz commit Core, `/api/version`, model digest i `/api/show`, konfigurację Core (bez sekretów), sprzęt/sterownik, stan Whispera, liczbę klientów i rzeczywiste ustawienia procesu usługi Ollama. Brak jawnego OLLAMA_NUM_PARALLEL nie dowodzi konkretnej liczby slotów. Wszystkie zaobserwowane slot ID=0 sugerują brak wykazanej równoległości; wymagają weryfikacji logiem runnera i przebiegiem czasowym. Trzy żądania HTTP i trzy permitty Core nadal mogą oznaczać jedną generację i kolejkę.

Direct i Core uruchamiaj oddzielnie; żadnego równoczesnego benchmarku obu warstw. Sampler systemowy uruchamiaj **na hoście Ollamy**. Dla zdalnej Ollamy local nvidia-smi/RAM/ps opisuje host skryptu, nie serwer. CLI `ollama ps` także musi wskazywać ten sam endpoint (operator ustawia OLLAMA_HOST wyłącznie w sesji pomiarowej); manifest zapisuje endpoint API, nie zmienia env. GPU process allocation i RAM wymagają porównania czasów próbek; null/unavailable nie oznacza 0.

Logi (read-only, o ile konto ma dostęp):

```bash
date --iso-8601=seconds
journalctl -u ollama --since '2026-11-25 12:00:00' --until '2026-11-25 13:00:00' --no-pager > ollama-window.log
```

Operator ustawia rzeczywiste granice czasu po zakończeniu testu, w strefie hosta; manifest/resources używa UTC. Zachowaj logi poza publicznym repo; mogą zawierać dane środowiska. Przy analizie zaznacz start/stop/PID runnera, liczbę slotów, n_ctx, offloaded layers, reload/load/eviction/OOM i duration. Same `load_duration>0`, obecność modelu w `/ps` lub niezmieniony slot ID nie dowodzą restartu ani jego braku.

## Benchmark bezpośredniej Ollamy

Uruchom z katalogu zawierającego skrypt; katalog `--out` musi być nowy:

```bash
python3 benchmark_inference.py --mode ollama \
  --model gpt-oss:20b --contexts 4096 8192 16384 32768 \
  --parallel 1 2 3 --kinds short long --repeats 3 \
  --output-tokens 256 --think low --timeout 180 --warmup --mixed \
  --out results-direct
```

Macierz 4 konteksty × 3 klientów × 2 długości × 3 powtórzenia; dodatkowo mieszane 1/2 zadania tła + opóźnione krótkie interaktywne żądanie, 3 powtórzenia. W direct „interactive/background” to **etykiety klienta**, API Ollamy nie otrzymuje priorytetu z Core. Zapis delay i actual intervals pozwala sprawdzić, czy tło jeszcze trwało w chwili wysłania chat. Jeśli zakończyło się wcześniej, próba nie dowodzi zachowania pod obciążeniem; zwiększ output albo zmniejsz delay i powtórz świadomie.

Krótki prompt to jedno pytanie; długi to syntetyczna tabela 100 rekordów plus prośba o streszczenie. Nie twierdzimy, że długi prompt zapełnia 32K: porównujemy stały input przy różnym przydziale fizycznego kontekstu. Rzeczywiste długości zapisuje prompt_eval_count. Do testu niemal pełnego kontekstu przygotować osobny ustalony corpus, odjąć output/template/margin i kwalifikować go licznikiem runtime; nie przekraczać modelMax. Powtórzyć reasoning medium i typowe 2048 output osobnym uruchomieniem, jeśli wymagane.

`--warmup` zapisuje wyłączoną z analizy próbę przed blokiem kontekstu; nie jest gwarancją całkowicie rozgrzanego cache. UUID promptu ogranicza identyczne trafienia prefix cache. Wyniki grupowane są według kontekstu, by nie wymuszać reload co request. Porównać także serię bez warmup; bez automatycznego unload nie nazywać jej cold start, jeśli model już był rezydentny.

Celowany test churn, osobne okno:

```bash
python3 benchmark_inference.py --mode ollama --contexts 4096 \
  --parallel 1 --kinds short --repeats 1 --alternate-contexts \
  --out results-context-switch
```

Dodatkowe 4096→8192→4096→8192 z logami/PID. Nie mieszać różnych num_ctx jednocześnie w podstawowej macierzy; test konkuru­jących kontekstów wymaga osobnego eksperymentu, ponieważ może wymuszać drain/reload. Nie zmieniać automatycznie OLLAMA_NUM_PARALLEL: jeśli operator zdecyduje się osobno porównać ustawienia 1/2/3, powinien wykonać izolowane okna i zapisać runtime config; liczba klientów jest innym wymiarem niż liczba slotów.

## Benchmark pełnego Core

Core ma własne profile/model/kontekst: flagi model/context/output/think skryptu **nie nadpisują Core**. Macierz num_ctx dotyczy direct. Testy Core różnych stabilnych kontekstów i limitów gateway=1/2/3 wymagają oddzielnie przygotowanej instancji testowej albo świadomej ręcznej konfiguracji przez operatora; skrypt nigdy tego nie robi. W manifest zapisać rzeczywiste wartości, digest i modelMax; nie porównywać niezidentyfikowanych konfiguracji.

Użyj konta testowego, bearer token w `JARVIS_BENCH_TOKEN` (nie parametr CLI). Syntetyczne rozmowy oraz zadania pozostaną w historii i mogą wywołać tytuły/pamięć. Mixed zleca rzeczywiste Companion taski przez REST; cleanup anuluje wyłącznie zadania tej próby, bez usuwania historii. Nie używać promptów narzędzi wykonujących efekty uboczne.

```bash
# JARVIS_BENCH_TOKEN ustawiony poza historią poleceń zgodnie z lokalną procedurą.
python3 benchmark_inference.py --mode core \
  --core http://127.0.0.1:8080 --ollama http://127.0.0.1:11434 \
  --parallel 1 2 3 --kinds short long --repeats 3 --mixed \
  --timeout 180 --out results-core
```

Zwykłe próby: oddzielny conversationId per request, POST stream-token + GET SSE. Mixed: 1/2 taski Companion, potem krótki chat; zapis stanów zadań przy starcie chat. RUNNING nie dowodzi aktywnej inferencji tła — połącz z logami gateway i trace. Brak narzędzi Companion w rozmowie oznacza, że ten test bada endpoint task engine, **nie semantyczne delegowanie przez LLM**. Osobny scenariusz E2E po implementacji narzędzia: „uruchom research w tle” → receipt/task.created → Overlay → dalszy chat → completion z taskId.

## Metryki i uczciwe ograniczenia

| Metryka | Direct | Core |
|---|---|---|
| TTFT any / visible | czas klienta do niepustego thinking/content | do THINKING_TOKEN / ANSWER_TOKEN lub TOKEN w SSE; obejmuje przygotowanie stream token |
| Gateway queue | nie dotyczy | suma OLLAMA_QUEUE_WAIT FINISHED z trace, jeśli obecne; inaczej null |
| Ollama runtime queue | null, API nie daje dokładnego czasu | null bez dodatkowego runtime pomiaru |
| Prompt tok/s | prompt_eval_count / prompt_eval_duration | null; powiązać z logami per inference |
| Generation tok/s request | eval_count / eval_duration | null bez czasu runtime; TOKEN_USAGE nie zawiera tego czasu |
| Aggregate tok/s | suma eval_count / wall całej grupy; tylko kompletne wyniki | zwykłe grupy: outputTokens dedup po callId / wall, jeśli TOKEN_USAGE dostępne; mixed null bez kompletnej telemetrii zadań |
| Całkowity czas | monotonic per request i grupa | jw., oddzielne stany zadań |
| VRAM/RAM/CPU/GPU | resources.jsonl w tle | ten sam sampler, kontekst Core osobno |
| Offload | ollama ps, runner log, /ps raw | jw.; bez utożsamiania size_vram/size z procentem warstw |
| Reload | load_duration jako wskazówka + runner PID/log | log/PID; sampler może przegapić krótki restart |
| Błędy/timeout | error per request; incomplete stream to błąd | także terminal states tasków; nie usuwać failed z mianownika statystyki |

Nie odejmować prompt_eval+eval+load od wall i nazywać różnicy dokładną kolejką: pozostają transport, pre/postprocessing i scheduler overhead. Nie traktować chunków SSE jako tokenów. Dla Core aggregate output mierzy wszystkie raportowane rundy (może obejmować reasoning), a nie wyłącznie widoczny tekst. W direct num_predict także ogranicza łączną generację, więc widoczna odpowiedź może nie wystąpić przed limitem.

Artefakty: manifest.json, results.jsonl, resources.jsonl oraz osobno logi operatora. Osobny wiersz raportu dla kontekstu, klientów HTTP, rzeczywistych slotów runtime, długości promptu, reasoning, offload i Whisper state. Obliczyć p50/p95 TTFT/wall (dla wiarygodnego p95 zebrać >=20 prób), błędy/timeouty, per-request i aggregate tok/s, peak GPU/RAM, reload count potwierdzony logami. Nie mieszać warmup/reload probe z steady state. Wybór konfiguracji wymaga poprawy interaktywnego SLO przy akceptowalnym wykorzystaniu pamięci, nie tylko wyższego aggregate.

## Weryfikacja lokalna i dalsze testy

`python -m unittest discover -s SHARED -p test_benchmark_inference.py -v`: 5 testów transportu zakończonych powodzeniem (2026-11-25): rzeczywisty lokalny HTTP overlap 1/2/3, stawki runtime, Core SSE i deduplikacja usage, przerwany stream, HTTP 503 i deadline. Sandbox początkowo blokował loopback; test powtórzono z dopuszczonym lokalnym dostępem. To walidacja harnessu, nie produkcyjnego Core/Ollamy. CLI `--help` sprawdzony. Brak benchmarku sprzętu i brak potwierdzenia równoległych slotów.

Przed kwalifikacją produkcyjną: Ubuntu smoke z 1/4096/short, kompletność wszystkich plików, zgodność bearer auth/SSE i logów czasu, test Whisper idle/active w osobnych oknach, monitoring OOM/cgroup, porównanie stabilnych logical profiles ze stałym fizycznym num_ctx. Nie wykonano testów Maven, ponieważ aplikacja Java nie jest zmieniana; nowe testy runtime/provider opisano w audycie. Lista plików kolejnego etapu i kontraktów znajduje się w pozostałych trzech raportach.


## Aktualizacja Phase 2 — 24K, cztery sloty i rzeczywiste fazy Core

Najnowsze pomiary operatora i opóźnienia podczas prefillu: `OLLAMA_CONCURRENCY_BENCHMARKS.md`. Zalecany fizyczny kontekst to stałe **24576**, cztery sloty Ollamy, flash attention i KV q8_0. Skrypt dopuszcza teraz `--parallel 4`, więc mixed może uruchomić **trzy zadania tła i jedną rozmowę**. Przykład osobnego okna operatora:

```bash
python3 benchmark_inference.py --mode ollama --contexts 24576 --parallel 1 2 3 4 --mixed --repeats 3 --out results-24k
```

Nie uruchomiono tej komendy na produkcji. Bezpośredni tryb Ollamy nie ma priorytetu Core. Oddzielny eksperyment Core porównuje konfigurowane odstępy admission tła 0/100/250/500 ms; operator ustawia je świadomie na instancji testowej, skrypt ich nie zmienia. Odstęp 250 ms jest hipotezą do kwalifikacji, nie potwierdzonym optimum. Mierz TTFT i pierwszą treść osobno dla okresu prompt processing i generation. Żaden odstęp ani rezerwa slotu nie gwarantuje preempcji GPU.

Core Phase 2 emituje teraz OLLAMA_METRICS także dla native tool chat. Harness zapisuje runtimeMetrics i wylicza promptTokPerSec / generationTokPerSec jako sumę tokenów podzieloną przez sumę odpowiednich zmierzonych czasów **tylko gdy każda raportowana inferencja ma odpowiadające kompletne timing/usage**. W przeciwnym razie stawka pozostaje null; dane per call można obejrzeć osobno. phaseTimingsMeasured=false nie jest czasem 0. Są to czasy runtime z końcowego komunikatu Ollamy, a nie pomiar bieżącej fazy ani dokładnej kolejki wewnętrznej. Zastępuje to wcześniejsze ograniczenie tabeli Core, w której stawki pozostawały zawsze null.

Końcowy lokalny harness: 5 testów przeszło, w tym overlap HTTP dla 1/2/3/4 klientów, SSE usage/timing, deadline, HTTP error i przerwany stream. Nie jest to benchmark wydajności sprzętu produkcyjnego.
