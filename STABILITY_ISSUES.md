# Core — problemy stabilizacji

| ID | Priorytet | Stan | Problem / dowód |
|---|---|---|---|
| CORE-01 | P0 | PASS (lokalny REST) | Windows zgłasza brak parameterSchema.llamacpp; metoda state zawiera schemat, dowód finalnego JAR w inference-state-http.json: schemat 24 pól, ADMIN 200 / brak sesji 401 / USER 403. |
| CORE-02 | P0 | PASS (audyt i regresja) | Audyt aktywnego silnika dla wszystkich wywołań, w tym startup/background/title/memory. |
| CORE-03 | P0 | PASS (celowana regresja i live) | Poprzedni realny audyt: model dopisał NASDAQ do niezweryfikowanego CSS i interpretował kod pogody bez legendy. Wynik częściowy nie powinien dopisywać faktów o brakujących źródłach. |
| ENV-01 | P0 | ENVIRONMENT_LIMITATION | Lokalnie brak produkcyjnego Ubuntu/GGUF/mmproj/template i RTX 4060 Ti 16 GiB; nie zastępować rzeczywistych testów mockami oznaczonymi PASS. |
| ENV-02 | P1 | ENVIRONMENT_LIMITATION / BLOCKED | Dostępność SearXNG, Bridge i autoryzowanego konta Gmail/integracji do sprawdzenia. |

Nie kopiować statusów historycznych testów jako dowodu bieżącego wydania.

## Dodatkowe ustalenia i poprawki
- CORE-04 / P0: synteza wymagała STRUCTURED_OUTPUT mimo profilu TOOLS — FIX IMPLEMENTED, regresja native envelope + transport HTTP + API synthesis.
- CORE-05 / P0: warmup mógł wejść do Ollamy po zmianie silnika — FIX IMPLEMENTED, konfiguracja pinned/STALE po admission.
- CORE-06 / P0: moderacja omijała aktywny provider — FIX IMPLEMENTED, active AIProvider + deadline, bez poluzowania parsera.
- CORE-07 / P0: GPU embeddingi Ollamy obok llama.cpp — FIX IMPLEMENTED, CPU auxiliary zachowane, GPU fail closed.
- CORE-08 / P0: obce drafty wiedzy listowane/zatwierdzane/odrzucane — FIX IMPLEMENTED, kontrola właściciela przed usunięciem.
- CORE-09 / P1: capabilities katalogu ignorowały zapisany profil — FIX IMPLEMENTED, deklaracje odtworzone, verified nadal oddzielne.
- CORE-03: guard giełd w odpowiedzi częściowej i buforowanie przed publikacją; legenda WMO z dokumentacji narzędzia. To celowana ochrona, nie gwarancja dowolnych twierdzeń LLM.
- CR-03 / ograniczenie kontraktu: surowe THINKING_TOKEN celowo nie są publiczne; zachowano zabezpieczenie. Brak tekstu nie naprawia się zmianą klucza UI.
- NOT_IMPLEMENTED: osobne API zapisania profilu nieaktywnego bez aktywacji; istniejący wybór+parametry aktywuje transakcyjnie i dopiero potem zapisuje profil.
- NOT_IMPLEMENTED: samodzielna implementacja jarvis-planner (istnieją interfejsy); nie dotyczy działającego planowania agentów.

- CORE-10 / P0 / FAIL w rzeczywistej próbie długiego kontekstu: zduplikowana bieżąca wiadomość i historia w obowiązkowym system prompt routera. Poprawka przygotowana (nativeSystemPrompt/ograniczone referencje); pełna regresja i realny re-test wymagane przed oznaczeniem PASS.
- TEST-01: weryfikator daty dokumentu odrzucił U+2011 zamiast ASCII '-'. Odczyt faktycznie poprawny; normalizować wyłącznie łączniki prezentacyjne.
- Kontrakt wiedzy: katalog Vault pozostaje wspólny, nie jest nową prywatną przestrzenią per konto. Izolacja draftów i pamięci/rozmów/zadań nie oznacza izolacji całego wspólnego katalogu wiedzy.

## Rozstrzygnięcie końcowe
- CORE-10: PASS po poprawce i finalnej regresji. Rzeczywisty finalny JAR przeszedł 4 długie tury + recall bez przepełnienia; stary FAIL zachowany jako repro.
- CORE-11: błędne 503 dla przekroczenia kontekstu naprawione na bezpieczne 422. Regresja ApiExceptionHandlerTest oraz rzeczywisty fault-audit PASS.
- CORE-04..09: PASS w opisanym zakresie regresji; produkcyjny llama nadal NOT_VERIFIED. Nie oznaczać braku docelowego sprzętu jako naprawy produkcyjnej.
- TEST-02: niestabilny test TTL workspace zastąpiono deterministycznym postarzeniem zapisanych metadanych i odświeżeniem aktywnego workspace; produkcyjnego TTL nie zwiększano.
- Historia: head/tail referencje są ograniczone; fakt ze środka dowolnej starej długiej wiadomości nie jest objęty potwierdzoną próbą. Pełna historia w SQLite, brak automatycznego narzędzia odczytu jej dowolnego fragmentu (NOT_VERIFIED w pipeline).
- Pamięć autonomiczna: zapis/ekstrakcja/restart/izolacja PASS lokalnie; podłączenie tych rekordów do głównego promptu NOT_IMPLEMENTED. Nie dodawano nowej funkcji.
- ENV-01 pozostaje otwarte: brak pomiaru VRAM i realnego przełączenia dwóch silników; wymagany produkcyjny audyt operatora. ENV-02: SearXNG niedostępne, konta/bridge niepodłączone.

- CORE-12 / P0 / PASS (celowana regresja i live): niepełny plan pomijał jawny identyfikator; kontrola kompletności identyfikatorów i ograniczona naprawa przed startem workerów. Nie jest to pełny deterministyczny dowód pokrycia dowolnej semantyki.
- CORE-13 / P1 / PASS (live finalnego JAR): dokument Knowledge Vault skierowany do browser/Windows. Rozdzielono instrukcje źródeł knowledge/browser/web; powtórny odczyt poprawny. Zachowano oryginalny FAIL i pełną regresję.
