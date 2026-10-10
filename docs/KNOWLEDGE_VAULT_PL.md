# Wiedza Jarvisa jako vault Obsidiana

Wiedza Jarvisa to zwykłe pliki Markdown w katalogu `knowledge.root` na serwerze Ubuntu. Obsidian służy do ich
wygodnej edycji i przeglądania, ale Core **nie wymaga** Obsidiana, pluginów ani Obsidian Sync. Markdown jest
źródłem prawdy; indeks fragmentów (SQLite) można w każdej chwili usunąć i odbudować.

## 1. Architektura

```
 Windows: okno „Pamięć i wiedza”           Obsidian (opcjonalnie, lokalna kopia vault)
            │  /api/v1/vault (REST, token)
            ▼
 Core (Ubuntu) ─ VaultDocumentService ── edycje użytkownika (kontrola wersji sha256, historia .history)
      │         KnowledgeVaultService ── indeks fragmentów, wyszukiwanie, odczyt workflow, źródła
      │              ├─ VaultPathPolicy   bezpieczne ścieżki, wykluczenia (.obsidian, .git, tmp, sekrety, prompt)
      │              ├─ VaultIndexer      skan → parser Markdown/YAML → chunker (nagłówki/akapity/tokeny)
      │              │                    → SQLite data/knowledge-index.db → embeddingi w tle
      │              ├─ VaultSearchService BM25 (polski stemming) + cosine, fuzja RRF, filtry, budżet
      │              └─ VaultDocumentReader całe dokumenty lub jawne części
      ├─ KnowledgeTool (TOOL_REQUEST/TOOL_RESULT bez zmian) ── SEARCH_CONTENT, READ_DOCUMENT, FIND_WORKFLOW, READ_WORKFLOW
      └─ istniejące: DefaultKnowledgeService (metadane), watcher, workspace (drafty, historia)
 Embeddingi: none | core | ollama (CPU) | openai (np. text-embeddings-inference CPU)
```

Nowy kod rozbudowuje istniejące mechanizmy: ten sam katalog `knowledge.root`, ten sam watcher (debounce),
ta sama historia wersji (`.history`), te same drafty, to samo narzędzie `knowledge`, ta sama pętla agenta.

## 2. Tryby, flaga i powrót

| `knowledge.vault.mode` | Zachowanie |
|---|---|
| `LEGACY` (domyślnie) | dotychczasowy mechanizm: wyszukiwanie dokumentów po tytule/ścieżce/podglądzie 500 znaków. Brak indeksu fragmentów, brak nowych embeddingów. Okno Windows działa (przeglądanie, edycja), wyszukiwanie pokazuje wyniki starego mechanizmu. |
| `VAULT` | indeks pełnej treści, `SEARCH_CONTENT` zwraca fragmenty ze źródłami, `READ_DOCUMENT` czyta całość lub jawne części, dochodzą `FIND_WORKFLOW` i `READ_WORKFLOW`. |

Włączenie: `JARVIS_KNOWLEDGE_VAULT_MODE=VAULT` i restart Core.

**Powrót:** `JARVIS_KNOWLEDGE_VAULT_MODE=LEGACY` i restart. Pliki Markdown nie są zmieniane przez indeks.
Opcjonalnie usuń `data/knowledge-index.db*`. Jeśli wykonano migrację z `--apply`, jej kopia zapasowa leży w
`backups/vault-<data>/` (migracja zmienia tylko frontmatter i ewentualnie ścieżki z jawnego planu).

Zmiany wspólne dla obu trybów (poprawki błędów, nie zmiana zachowania wyszukiwania):
- identyfikatory dokumentów w indeksie metadanych są deterministyczne (z `id` we frontmatter albo ze ścieżki) —
  wcześniej losowe przy każdym restarcie, przez co historia `.history/<id>` „gubiła” dokument;
- wykluczenia (`.obsidian`, `.git`, pliki tymczasowe/kopie, sekrety, kopie promptu) obowiązują też stary indeks,
  watcher i `LIST_TREE`/`READ_DOCUMENT` — wcześniej np. `.obsidian/*.json` trafiałby do indeksu;
- watcher odświeża indeks po przeniesieniu/zmianie nazwy całego folderu (wcześniej pliki w przeniesionym folderze ginęły z indeksu).

## 3. Konfiguracja (przykład bez sekretów)

`application.yml` zawiera wszystkie klucze z domyślnymi wartościami. Na Ubuntu wystarczą zmienne środowiskowe
(np. w `/etc/jarvis/jarvis.env` używanym przez usługę systemd):

```bash
JARVIS_KNOWLEDGE_ROOT=/opt/jarvis/knowledge          # vault = istniejący katalog wiedzy
JARVIS_KNOWLEDGE_VAULT_MODE=VAULT
JARVIS_KNOWLEDGE_INDEX_DB=/opt/jarvis/data/knowledge-index.db
# Embeddingi na CPU przez text-embeddings-inference (rekomendowane, jak w laboratorium):
JARVIS_KNOWLEDGE_EMBEDDING_PROVIDER=openai
JARVIS_KNOWLEDGE_EMBEDDING_URL=http://127.0.0.1:8090
JARVIS_KNOWLEDGE_EMBEDDING_MODEL=intfloat/multilingual-e5-base
JARVIS_KNOWLEDGE_EMBEDDING_DIMENSIONS=768
JARVIS_KNOWLEDGE_EMBEDDING_QUERY_PREFIX="query: "
JARVIS_KNOWLEDGE_EMBEDDING_PASSAGE_PREFIX="passage: "
JARVIS_KNOWLEDGE_EMBEDDING_MAX_TOKENS=512
JARVIS_KNOWLEDGE_EMBEDDING_TOKENIZER=remote          # prawdziwe liczenie tokenów przez /tokenize
# JARVIS_KNOWLEDGE_MIN_SIMILARITY=0.80               # dostroić benchmarkiem (patrz VAULT_BENCHMARK_PL.md)
```

### Provider embeddingów — wybór

| Provider | Kiedy | Uwagi |
|---|---|---|
| `openai` + text-embeddings-inference (CPU) | **rekomendowany** | mała osobna usługa, bez Pythona/CUDA, model e5-base sprawdzony w laboratorium, `/tokenize` daje prawdziwe tokeny, za długie wejście jest odrzucane (413), a Core dzieli fragment dalej |
| `ollama` | gdy nie chcesz Dockera | dedykowany model embeddingów przez `/api/embed` z `num_gpu=0` (CPU, nie zabiera VRAM) i `truncate=false`. intfloat/multilingual-e5-base nie jest w oficjalnej bibliotece Ollamy; wielojęzyczny `bge-m3` jest (1024 wymiary, bez prefiksów) — wymaga zmiany wymiaru i ponownego benchmarku |
| `core` | szybki test | używa istniejącego beana `EmbeddingProvider` (`jarvis.memory.embedding.model`, domyślnie `nomic-embed-text`, GPU, bez prefiksów, model anglojęzyczny) |
| `none` | domyślnie | tylko słowa kluczowe (BM25 z polskim stemmingiem) |

Uruchomienie TEI na CPU (sprawdź aktualny tag obrazu `cpu-*`):

```bash
docker run -d --name jarvis-embeddings --restart unless-stopped -p 127.0.0.1:8090:80 \
  -v /opt/jarvis/tei-data:/data ghcr.io/huggingface/text-embeddings-inference:cpu-1.5 \
  --model-id intfloat/multilingual-e5-base
curl -s http://127.0.0.1:8090/v1/embeddings -H 'Content-Type: application/json' \
  -d '{"model":"intfloat/multilingual-e5-base","input":["query: test"]}' | head -c 200
```

Ochrona przed mieszaniem przestrzeni wektorów: indeks zapisuje „odcisk” providera (provider, model, prefiksy,
normalizacja) i wymiar. Po zmianie modelu lub wymiaru stare wektory są usuwane i liczone od nowa; wektor o innym
wymiarze jest odrzucany. Awaria providera nie blokuje wyszukiwania — wynik jest jawnie oznaczony jako tylko
tekstowy (`effectiveMode=TEXT`, `semanticError`), a brakujące wektory są liczone ponownie w tle.

## 4. Struktura vault

Propozycja (istniejące foldery i ścieżki zostają — `Work/Scheduling/StoreAuditScheduleWorkflow.md` dalej działa):

```
workflows/     procedury do wykonania (type: workflow)
projects/      projekty
knowledge/     wiedza: serwer, technologie, sieć…
preferences/   jawne preferencje
decisions/     decyzje z uzasadnieniem
archive/       nieaktualne wersje (domyślnie pomijane w wyszukiwaniu)
attachments/   załączniki (obrazy, PDF) – listowane i linkowane, bez OCR
```

Frontmatter jest opcjonalny:

```yaml
---
id: proj-nova            # stabilny identyfikator (przeżywa zmianę nazwy i przeniesienie)
title: Projekt Nova
type: project            # workflow | knowledge | project | preference | decision
tags: [nova, budżet]
status: active           # archived / inactive / deprecated / superseded → ukryte domyślnie
version: 3
updated: 2026-09-20
---
```

Workflow rozpoznawany jest po `type: workflow`, folderze `workflows/` albo nazwie `*Workflow.md`.
Obsługiwane są linki `[tekst](inny%20plik.md)`, wikilinki `[[Notatka]]`, `[[Notatka#Nagłówek|alias]]`,
osadzenia `![[obraz.png]]` oraz tagi `#tag`. Polskie znaki i spacje w nazwach są w pełni obsługiwane.

Nigdy nie są indeksowane: `.obsidian/`, `.git/`, `.trash/`, `.history/`, `.drafts/`, pliki tymczasowe
(`*.tmp`, `*~`, `.~lock.*`, `*.swp`), kopie (`*.bak`, `*.orig`, `*.sync-conflict-*`), pliki z sekretami
(`.env*`, `*.pem`, `*.key`, `*secret*`, `*credential*`, `*password*`, a także notatki, w których wykryto token/klucz
— widoczne w drzewie z ostrzeżeniem), oraz prompt systemowy `config/jarvis.md` i jego kopie (po nazwie i po treści).

## 5. Przygotowanie vault i otwarcie w Obsidianie

1. Na Ubuntu vault to `JARVIS_KNOWLEDGE_ROOT` (np. `/opt/jarvis/knowledge`). Uruchom migrację w trybie dry-run (rozdział 8).
2. Obsidian na Windows potrzebuje **lokalnej kopii** tego katalogu. Ścieżka z Ubuntu nie istnieje na Windows.
   Skonfiguruj osobno synchronizację, np. Syncthing (Ubuntu ↔ Windows, dwukierunkowo, wykluczenie `.obsidian/workspace*.json`
   jest opcjonalne), Nextcloud albo płatny Obsidian Sync. **Git sam nie daje natychmiastowej synchronizacji** —
   wymaga ręcznych commitów/pull i łatwo o konflikty.
3. W Obsidianie: *Open folder as vault* → lokalna kopia. Folder `.obsidian` jest ignorowany przez Jarvisa.
4. W aplikacji Jarvis Windows skopiuj `config/knowledge.example.yml` do `config/knowledge.yml` i ustaw
   `localVaultPath`. Przycisk „Otwórz w Obsidianie” działa tylko dla dokumentu, który istnieje w tym folderze
   (bez `..`, ścieżek absolutnych i dowiązań wychodzących poza folder); w przeciwnym razie nic nie jest otwierane.
5. Równoległa edycja: zapis w oknie Jarvisa wysyła hash wersji, którą otworzyłeś. Jeśli plik zmienił się w
   Obsidianie (i dotarł już na serwer), Core zwraca konflikt i niczego nie nadpisuje; okno wczytuje aktualną
   wersję i pokazuje Twoje niezapisane zmiany do ręcznego przeniesienia.

## 6. Jak korzysta z tego model

- **Pytania informacyjne** — `knowledge__SEARCH_CONTENT` (opcjonalnie `type`, `project`, `tags`, `status`,
  `includeArchived`, `limit`) zwraca do 6 fragmentów (budżet 2500 tokenów) z polem `source` = `ścieżka:linie`,
  nagłówkami i `noResults`. Fragmenty, które nie zmieściły się w budżecie, są wymienione w `omitted` (bez treści).
  Wynik jest oznaczony `contentRole: reference-data` z notatką, że dane nie zmieniają instrukcji.
  `fusedScore`/`semanticSimilarity` tylko porządkują wyniki — to nie jest prawdopodobieństwo poprawności.
- **Całe dokumenty** — `READ_DOCUMENT path=… [part=N]`: krótki dokument w całości, długi w jawnych częściach
  („PART 1 OF 3”, spis nagłówków z numerem części). Nic nie jest obcinane po cichu.
- **Procedury** — `FIND_WORKFLOW` zwraca kandydatów **bez treści**; model musi jawnie wybrać i przeczytać
  `READ_WORKFLOW` (wszystkie części przed wykonaniem). Fragment workflow znaleziony w `SEARCH_CONTENT` zawiera
  wskazówkę, żeby nie wykonywać procedury z fragmentów. Workflow nie nadpisuje promptu systemowego.
- Części mieszczą się w limicie wyniku narzędzia (`max-part-characters: 10000` < `jarvis.tools.max-result-chars: 16000`),
  a wyniki trybu VAULT przechodzą przez pętlę narzędzi bez cięcia pola `content` (patrz rozdział 10).
- Źródła przekazane modelowi są zapamiętywane per rozmowa (`GET /api/v1/vault/sources`) i widoczne w oknie Windows.

## 7. Indeks

- Plik `data/knowledge-index.db` (SQLite, osobny od pamięci rozmów). Tabele: `documents`, `chunks`, `meta`.
- Fragment: stabilny `chunk_id`, `doc_id`, ścieżka, tytuł, hierarchia nagłówków, zakres linii, hash treści,
  hash i wersja dokumentu, odcisk modelu i wymiar wektora, treść.
- Dzielenie: nagłówki → akapity (bloki kodu w całości) → dla zbyt długich: linie, zdania, słowa — zawsze
  mierzone licznikiem tokenów modelu (`remote`) lub zachowawczym estymatorem (`heuristic`), nigdy znakami.
- Po zmianie pliku (watcher, debounce 1,5 s) przeliczane są tylko zmienione dokumenty; wektory niezmienionych
  fragmentów są ponownie używane. Przeniesienie/zmiana nazwy zachowuje `id` (z frontmatter albo po identycznej
  treści). Usunięte pliki znikają z indeksu. Ponowny skan nie tworzy duplikatów.
- Po restarcie indeks jest od razu dostępny z SQLite, a skan w tle sprawdza tylko zmiany (rozmiar/mtime → hash).
- Status: `GET /api/v1/vault/status` (stan, liczby dokumentów/fragmentów/oczekujących, błędy dokumentów, provider).

## 8. Migracja (dry-run)

```bash
cd /opt/jarvis
# 1) podgląd — nic nie jest zmieniane
java -cp jarvis-core.jar -Dloader.main=com.jarvis.knowledge.vault.migration.VaultMigrationCli \
  org.springframework.boot.loader.launch.PropertiesLauncher \
  --vault ./knowledge --prompt config/jarvis.md --plan config/vault-migration.yml
# 2) wykonanie — najpierw pełna kopia do ./backups/vault-<data>/
java ... VaultMigrationCli ... --vault ./knowledge --plan config/vault-migration.yml --apply
```

Raport (`data/vault-migration/<czas>/migration-report.md` i `.json`) zawiera mapę źródło → cel, nadawane `id`
(istniejące `id` są zachowane; jeśli istnieje historia wersji dokumentu, jej identyfikator jest reużywany), foldery
do utworzenia oraz listę spraw do decyzji: identyczne dokumenty, obcięte kopie, podobne dokumenty, nazwy
niepasujące do treści, konflikty celów. Nic nie jest scalane ani usuwane automatycznie. Ponowne uruchomienie
nic nie zmienia (idempotentne). Historia rozmów nie jest dotykana.

Wynik dry-run na obecnym `knowledge/` w repozytorium:
- `GraphicDesignWorkflow.md` — **potwierdzony problem**: cała treść (7 649 zn.) to początek
  `Work/Scheduling/StoreAuditScheduleWorkflow.md` (14 518 zn.), czyli obcięta kopia procedury audytów sklepów,
  a nie workflow projektowania grafiki. Prompt (sekcja 29) wskazuje nieistniejący `Work/Creative/GraphicDesignWorkflow.md`.
  Propozycja w `config/vault-migration.example.yml` (zakomentowana, wymaga decyzji): przenieść do `archive/`
  ze `status: archived`. Nie utworzono zastępczego workflow graficznego.

## 9. Okno Windows „Pamięć i wiedza”

Przycisk w panelu CORE (obok „System Instructions”). Drzewo folderów (foldery, notatki, załączniki, ⟳ workflow,
⚠ wykluczone), wyszukiwarka hybrydowa/tekstowa z filtrami typ/projekt/tagi/status/archiwalne, wyniki z
podświetleniem fragmentu w dokumencie, edytor Markdown z podglądem (Ctrl+S), szczegóły (id, typ, tagi, ścieżka,
wersja, daty, stan indeksu, linki i linki zwrotne), historia wersji z podglądem i przywracaniem, drafty Jarvisa
(zatwierdź/odrzuć), źródła użyte w aktywnej rozmowie, nowy dokument/folder, zmiana nazwy/przeniesienie,
reindeksacja i przebudowa indeksu, pasek statusu Core/indeksu/embeddingów. Wszystkie wywołania są asynchroniczne.

Zrzuty z prawdziwego okna połączonego z lokalnie uruchomionym Core: `docs/images/knowledge-window-*.png`.

## 10. Znalezione problemy w obecnym mechanizmie

1. **Ciche obcinanie dokumentów w trybie LEGACY.** `NativeToolLoopService.compactData` skraca pole `content` wyników
   narzędzia `knowledge` do 2 500 znaków (i listy do 15 elementów) bez informacji dla modelu. `READ_DOCUMENT` na
   `StoreAuditScheduleWorkflow.md` daje modelowi ok. 17% procedury. W trybie VAULT wyniki (oznaczone `contentRole`)
   omijają to cięcie; w trybie LEGACY zachowanie **nie zostało zmienione** (do decyzji, czy poprawić).
2. Losowe identyfikatory dokumentów przy każdym restarcie (naprawione, patrz rozdział 2).
3. „Semantyczny” retriever osadza tylko tytuł+ścieżkę+500 znaków podglądu; gdy provider jest niedostępny, przy każdym
   zapytaniu ponawia próbę osadzenia każdego dokumentu. Provider to `nomic-embed-text` przez Ollamę (GPU). Czy model jest
   pobrany na Ubuntu — niezweryfikowane. Na maszynie testowej (Windows) nie był, więc wyszukiwanie po cichu było tylko tekstowe.
4. `GraphicDesignWorkflow.md` i sekcja 29 promptu (rozdział 8).

## 11. API

| Metoda | Ścieżka | Opis |
|---|---|---|
| GET | `/api/v1/vault/status` | tryb, stan indeksu, provider |
| GET | `/api/v1/vault/tree` | drzewo |
| GET | `/api/v1/vault/document?path=` | treść + metadane + `version` |
| PUT | `/api/v1/vault/document` | `{path, content, expectedVersion}` → 409 przy konflikcie (z aktualną treścią) |
| POST | `/api/v1/vault/document`, `/folder` | tworzenie (409, gdy istnieje) |
| POST | `/api/v1/vault/move` | `{from, to, expectedVersion?}` (nigdy nie nadpisuje) |
| GET | `/api/v1/vault/search?q=&mode=text\|hybrid&type=&project=&tags=&status=&includeArchived=` | fragmenty |
| GET | `/api/v1/vault/read?path=&part=` | dokument tak, jak widzi go model |
| GET | `/api/v1/vault/workflows?q=` | kandydaci workflow |
| GET/POST | `/api/v1/vault/history`, `/history/version`, `/history/restore` | historia wersji |
| POST | `/api/v1/vault/reindex`, `/rebuild` | odświeżenie / przebudowa indeksu |
| GET | `/api/v1/vault/sources?conversationId=` | źródła przekazane modelowi |

Wszystkie ścieżki wymagają tokenu (jak reszta `/api/**`). Ścieżki z `..`, absolutne, z literą dysku lub
prowadzące przez dowiązanie poza vault zwracają 400; wykluczone lokalizacje 403.

## 12. Do zrobienia na Twoich maszynach

**Ubuntu:**
1. Uruchomić usługę embeddingów (TEI CPU, rozdział 3) albo zostawić `provider=none` (tylko słowa kluczowe).
2. Ustawić zmienne z rozdziału 3 w pliku środowiskowym usługi i zrestartować Core.
3. Migracja dry-run → przejrzeć raport → decyzja w sprawie `GraphicDesignWorkflow.md` → `--apply`.
4. Uruchomić benchmark z embeddingami i gemma4:12b (`VAULT_BENCHMARK_PL.md`) i dobrać `min-semantic-similarity`.
5. Jeśli Obsidian ma edytować vault: synchronizacja katalogu z Windows (Syncthing / Nextcloud / Obsidian Sync).

**Windows:**
1. Nowa wersja aplikacji (przycisk „Pamięć i wiedza” w panelu CORE).
2. Opcjonalnie `config/knowledge.yml` z `localVaultPath` dla „Otwórz w Obsidianie”.
