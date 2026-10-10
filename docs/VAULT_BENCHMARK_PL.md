# Benchmark: obecny mechanizm vs fragmenty (VAULT)

Wykonano 2026-10-10 na maszynie deweloperskiej Windows (nie na serwerze Ubuntu). Poniżej **zmierzony** raport
`VaultRetrievalBenchmarkTest` bez zmian. Uruchomienie:

```bash
mvn -o test -pl jarvis-knowledge -Dtest=VaultRetrievalBenchmarkTest
# z embeddingami (TEI):   JARVIS_BENCH_EMBEDDING_PROVIDER=openai JARVIS_BENCH_EMBEDDING_URL=http://127.0.0.1:8090 \
#   JARVIS_BENCH_EMBEDDING_MODEL=intfloat/multilingual-e5-base JARVIS_BENCH_EMBEDDING_QUERY_PREFIX="query: " \
#   JARVIS_BENCH_EMBEDDING_PASSAGE_PREFIX="passage: "
# z odpowiedziami modelu: JARVIS_BENCH_OLLAMA_URL=http://127.0.0.1:11434 JARVIS_BENCH_CHAT_MODEL=gemma4:12b
```

Zastrzeżenia (ważne przy interpretacji):
- **Embeddingi nie były użyte** — na tej maszynie nie ma modelu embeddingów; oba systemy działały tylko na słowach
  kluczowych. Część hybrydowa nie jest tu zmierzona.
- **Model odpowiedzi to `gpt-oss:20b`** (lokalna Ollama, `think: low`), a nie produkcyjny `gemma4:12b`.
- „Obecny + READ_DOCUMENT” odwzorowuje rzeczywiste zachowanie pętli narzędzi: treść dokumentu jest cięta do
  2 500 znaków (`NativeToolLoopService.compactData`).
- Ocena odpowiedzi modelu jest ścisła (wszystkie oczekiwane frazy muszą wystąpić dosłownie, po zdjęciu ogonków),
  więc poprawna parafraza („kanał 36” zamiast „kanale 36”) liczy się jako błąd — liczby są dolnym oszacowaniem dla obu systemów.
- Vault testowy (15 dokumentów, 34 pytania) przygotowałem na potrzeby tego zadania; to nie są Twoje dane.
  Benchmark trzeba powtórzyć na prawdziwym vault i gemma4:12b.

---

# Benchmark wyszukiwania wiedzy

- Pytania: 34 (32 z odpowiedzią w vault, 2 bez odpowiedzi)
- Vault testowy: 15 dokumentów (src/test/resources/vault-benchmark)
- Embeddingi: brak (oba systemy tylko słowa kluczowe)
- Tokeny liczone zachowawczym estymatorem HeuristicTokenCounter (nie tokenizerem modelu czatu)

## Retriever (bez modelu)

| System | Źródło @1 | Źródło @3 | Odpowiedź w kontekście | Brak wyniku dla pytań bez odpowiedzi | Czas śr. [ms] | Czas p95 [ms] | Kontekst śr. [tokeny] | Kontekst max [tokeny] |
|---|---|---|---|---|---|---|---|---|
| Obecny: SEARCH_CONTENT (podglądy 500 zn.) | 21/32 | 27/32 | 23/32 | 2/2 | 2.7 | 6 | 347 | 1037 |
| Obecny: SEARCH_CONTENT + READ_DOCUMENT top-1 (2500 zn.) | 21/32 | 27/32 | 25/32 | 2/2 | 2.7 | 6 | 726 | 1967 |
| Nowy: fragmenty (VAULT) | 30/32 | 31/32 | 31/32 | 2/2 | 1.1 | 3 | 295 | 606 |

## Szczegóły pytań (✓ = oczekiwany dokument w top-3 / odpowiedź w kontekście)

| # | Pytanie | Oczekiwany dokument | Obecny (szukaj) | Obecny (+czytaj) | Nowy |
|---|---|---|---|---|---|
| 1 | Jaki model czatu działa w Ollamie? | knowledge/Ollama i modele.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 2 | Ile VRAM ma karta graficzna serwera? | knowledge/Serwer domowy.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 3 | O której godzinie startuje nocna kopia zapasowa serwera? | knowledge/Serwer domowy.md | ✗ / ✗ | ✗ / ✗ | ✓ / ✓ |
| 4 | Na jakim porcie działa SSH na serwerze? | knowledge/Serwer domowy.md | ✗ / ✗ | ✗ / ✗ | ✓ / ✓ |
| 5 | Jaki zasilacz awaryjny chroni serwer? | knowledge/Serwer domowy.md | ✓ / ✗ | ✓ / ✗ | ✓ / ✓ |
| 6 | Jaki jest budżet projektu Nova? | projects/Projekt Nova.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 7 | Kiedy mija termin projektu Nova? | projects/Projekt Nova.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 8 | Jakiego modelu embeddingów używamy i ile ma wymiarów? | knowledge/Ollama i modele.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 9 | Dlaczego embeddingi liczymy na CPU? | decisions/2026-09 Embeddingi na CPU.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 10 | Dlaczego wybraliśmy SQLite zamiast pgvector? | decisions/2026-08 Magazyn indeksu.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 11 | Jak zrestartować usługi Jarvisa? | workflows/Restart usług Jarvisa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 12 | Ile dni przechowujemy kopie zapasowe vault? | workflows/Kopia zapasowa vault.md | ✓ / ✗ | ✓ / ✓ | ✓ / ✓ |
| 13 | Ile trwa jeden audyt w sklepie? | workflows/Audyt sklepów – planowanie.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 14 | Skąd startuje trasa audytów sklepów? | workflows/Audyt sklepów – planowanie.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 15 | Ile maksymalnie sklepów planujemy dziennie? | workflows/Audyt sklepów – planowanie.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 16 | W jakim formacie podawać daty? | preferences/Styl odpowiedzi.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 17 | Czy w odpowiedziach można używać emoji? | preferences/Styl odpowiedzi.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 18 | Od której godziny można planować spotkania? | preferences/Kawa i spotkania.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 19 | Jaki adres IP ma Pi-hole? | knowledge/Sieć domowa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 20 | Na którym kanale działa Wi-Fi 5 GHz? | knowledge/Sieć domowa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 21 | Gdzie stoi router? | knowledge/Sieć domowa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 22 | Jaki dysk zamontowano ostatnio w serwerze? | knowledge/Dziennik zmian serwera.md | ✓ / ✗ | ✓ / ✗ | ✓ / ✓ |
| 23 | Jakiego silnika używa rozpoznawanie mowy? | knowledge/Głos Jarvisa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 24 | Jaki jest docelowy czas odpowiedzi głosowej? | knowledge/Głos Jarvisa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 25 | W jakim frameworku napisano klienta Windows? | projects/Jarvis Windows.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 26 | Jaki jest znany problem klienta Windows po uśpieniu komputera? | projects/Jarvis Windows.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 27 | Czego nie wolno uruchamiać jednocześnie na GPU? | knowledge/Ollama i modele.md | ✗ / ✗ | ✗ / ✗ | ✓ / ✓ |
| 28 | W którym VLAN są urządzenia IoT? | knowledge/Sieć domowa.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 29 | Jak sprawdzić, czy kopia zapasowa vault się udała? | workflows/Kopia zapasowa vault.md | ✓ / ✗ | ✓ / ✓ | ✓ / ✓ |
| 30 | Jakie okno kontekstu ma model czatu? | knowledge/Ollama i modele.md | ✓ / ✓ | ✓ / ✓ | ✓ / ✓ |
| 31 | W jakim języku ma odpowiadać Jarvis? | preferences/Styl odpowiedzi.md | ✗ / ✗ | ✗ / ✗ | ✗ / ✗ |
| 32 | Ile czasu podtrzymuje UPS pracę serwera? | knowledge/Serwer domowy.md | ✗ / ✗ | ✗ / ✗ | ✓ / ✓ |
| 33 | Jaka jest stolica Australii? | — (brak w vault) | ✓ brak wyników | ✓ brak wyników | ✓ brak wyników |
| 34 | Ile kosztowała licencja programu Photoshop? | — (brak w vault) | ✓ brak wyników | ✓ brak wyników | ✓ brak wyników |

## Odpowiedzi modelu (gpt-oss:20b @ http://localhost:11434)

| System | Poprawne odpowiedzi | Poprawne „brak danych” | Czas śr. [s] | Przekroczenia czasu (3 min, liczone jako błąd) |
|---|---|---|---|---|
| Obecny: SEARCH_CONTENT + READ_DOCUMENT top-1 (2500 zn.) | 19/32 | 2/2 | 6.3 | 0 |
| Nowy: fragmenty (VAULT) | 21/32 | 2/2 | 2.7 | 0 |
