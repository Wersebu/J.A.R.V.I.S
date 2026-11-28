# Dla Claude: hotfix inference i kontekstu, kontrakt 1.9.0

Data: 2026-11-28. Core 2.24.0-SNAPSHOT; wydanie hotfix identyfikuj po SHA-256 w INFERENCE_CONTEXT_HOTFIX_METADATA.json. Kod Windows UI nie był edytowany. Ten dokument uzupełnia INFERENCE_1.9_CONTRACT.md; istniejące endpointy, uwierzytelnienie ADMIN i contractVersion=1.9.0 pozostają bez zmian. Schematy SERVER/docs/inference/schemas i SHARED/schemas/1.9.0 są zgodne.

## Szybki wybór i konfiguracja

POST /api/v1/inference/selection z engine i model, bez parameters lub z pustym obiektem, przywraca ostatnie zatwierdzone parametry dokładnie tego engine/model. Nowy model bez konfiguracji otrzymuje HTTP 409: code MODEL_CONFIGURATION_REQUIRED, zanim poprzedni silnik zostanie zatrzymany. Nie interpretuj obecności pliku GGUF jako gotowości do optymalnej pracy.

Niepuste parameters w POST /selection lub PUT /configuration to kompletna nowa konfiguracja. Nadal stosowane są domyślne wartości pominiętych parametrów podstawowych; pominięte opcje mmproj/chatTemplate/cache są usuwane. Edytor pełnych ustawień powinien wysłać cały zamierzony obiekt. Szybki selektor nie powinien sam dodawać wartości domyślnych. Stan per model jest zapisywany obok konfiguracji jako inference-engine.json.models.json.

ModelDescriptor zachowuje istniejące status AVAILABLE/INVALID i dodaje configurationRequired:boolean oraz configurationStatus:SAVED|REQUIRED. AVAILABLE dotyczy plików, nie gotowości modelu. Stary klient może zignorować nowe pola i nadal otrzyma czytelny błąd 409 przy niepełnej aktywacji.

parameterSchema.llamacpp rozszerzono o reasoning:on|off|auto; reasoningEffort nadal low|medium|high. parallelSlots ma zakres 1..16. GPU, Jinja, własny template, mmproj, flash attention, cache K/V i inne dotychczasowe parametry pozostają walidowane. Nie ma pola komendy shell ani dowolnych argumentów procesu. Zabezpieczenie STALE_CONFIGURATION pozostaje; stare zadania nie są przepisywane na nowy silnik.

## Limity requestu i kolejka

GET /state może zawierać runtimeLimits:

```json
{"contextTokensPerSlot":8192,"parallelSlots":3,"configuredTotalContextTokens":24576,"source":"BACKEND_PROPS"}
```

Gdy /props jest niedostępne, source=CONFIGURED_FALLBACK i per-slot jest bezpiecznym limitem obliczonym z konfiguracji. Nigdy nie prezentuj całego 24576 jako limitu jednej wypowiedzi przy trzech slotach. contextTokensPerSlot zawiera również miejsce zużywane przez prompt, narzędzia, obrazy oraz generowaną odpowiedź/reasoning; dostępny input jest mniejszy. ModelGateway ogłasza aktywną pojemność przez istniejące configuredConcurrency/gatewaySlotsConfigured.

Samo parallelSlots=3 nie jest nowym pomiarem przepustowości sprzętu. Zweryfikowane możliwości i deklaracje operatora pozostają rozdzielone. OCR i jakość długiego kontekstu pozostają NOT_VERIFIED/NOT_RUN.

## Tokeny: suma kosztu a zajęcie kontekstu

TOKEN_USAGE zachowuje inputTokens/outputTokens oraz totalInputTokens/totalOutputTokens. Rozszerzenia metadata:

- inputTokensThisCall i actualContextOccupancyTokens: liczba tokenów promptu zgłoszona przez backend dla tej jednej generacji, jeśli dostępna.
- generatedOutputTokensThisCall: tokeny output tej generacji.
- cumulativeInputTokens / cumulativeOutputTokens: suma wszystkich zmierzonych wywołań requestu. Nie jest to bieżące zajęcie context window.
- contextOccupancyIsCumulative:false, totalInputTokensMeaning:CUMULATIVE_ACROSS_MODEL_CALLS.

Nie zastępuj brakującego pomiaru zerem. Brak usage z backendu nie staje się zmierzonym wynikiem.

EXECUTION_TRACE z status CONTEXT_OCCUPANCY zawiera estimatedContextTokens, availableInputTokens, inputBudgetTokens, systemPromptTokens, conversationHistoryTokens, toolResultTokens, toolDefinitionTokens, toolCallTokens, imageReserveTokens i measurement=UTF8_HEURISTIC_NOT_TOKENIZER. To oszacowanie przygotowania promptu; zawartość pamięci/wiedzy złożona w system prompt wchodzi do jego kosztu. Nie jest to dokładny odczyt zajęcia KV cache. Status MODEL_CALL_USAGE rozdziela inputTokensThisCall, outputTokensThisCall oraz liczniki cumulative. Dotychczasowe zdarzenia można nadal parsować, ignorując nowe pola.

## Wyniki, stronicowanie i proste odczyty

Skrócony wynik ma _shortened z outputId i partial=true oraz ograniczone evidence. To częściowy widok danych. Pełny wynik jest zachowany w archiwum właściciela i dostępny przez output__read / output__grep. Ponowne skrócenie nie nadpisuje oryginału. Nie wnioskuj o pominiętych pozycjach, źródłach lub wykonaniu operacji.

output__read dodaje opcjonalne offsetChars (0-based) i maxChars (1..6000, domyślnie 3000), obok startLine/limit. Przy stronicowaniu znakowym zwraca nextOffsetChars, totalChars i partial. Dzięki temu można pobrać środek pojedynczej bardzo długiej linii JSON. Wynik web.READ_WEB_PAGE może mieć dodatkowy outputId pełnego znormalizowanego odczytu.

Nowe rodziny narzędzi są automatycznie odkrywane przez istniejący katalog, bez nowych ekranów lub endpointów Windows:

- weather.GET: location, mode=current|hourly|daily, opcjonalne date=YYYY-MM-DD|tomorrow, days 1..7, hours 1..24. JSON zawiera lokalizację/strefę, values, units, source i retrievedAt. Źródłem jest Open-Meteo. Nie oznaczać jako pomiar stacji meteorologicznej.
- exchange.RATE: currency, np. USD. Zwraca rate do PLN, effectiveDate, table, source i rateKind=OFFICIAL_REFERENCE_NOT_LIVE_MARKET. Nie pokazywać jako kursu kupna/sprzedaży ani real-time notowania.

Model-facing nazwy to weather__get i exchange__rate. Błędy WEATHER_UNAVAILABLE / EXCHANGE_UNAVAILABLE nie są danymi pogodowymi ani kursami. SearXNG pozostaje dostępnym narzędziem uniwersalnym.

Pogoda, kurs i pięć maili powinny być obsługiwane jako TOOLS w Main Conversation. Połączony odczyt nie wymaga automatycznego Task/Coding Agenta. Odpowiedzi informacyjne nie powinny wyświetlać raportu zmienionych plików, buildów i tur Coding. Szczegółowe liczniki znajdują się w diagnostyce. Trasy nadal wybiera model semantyczny, nie lista słów kluczowych.

## SSE i zachowanie pracy

Po rozpoczęciu SSE błąd kończy odpowiedź ramką event: ERROR z istniejącym CognitiveEvent, status FAILED, metadata.code=INFERENCE_FAILED, następnie zamyka strumień. Nie należy oczekiwać zwykłego ErrorResponse JSON w rozpoczętym text/event-stream. Błędy nie zawierają prywatnego stack trace. Nie zapisujemy błędu pipeline jako prawidłowej odpowiedzi AI.

Błędy budżetu wywołują deterministyczną kompresję i co najwyżej jedną ograniczoną końcową syntezę bez narzędzi, zamiast identycznych retry lub ponownego wykonywania zapisów. Możliwa jest jawnie częściowa odpowiedź. Historia, wyniki i identyfikatory istniejących tool_calls/tool results pozostają zachowane poza skróconym widokiem.

## Dostawa i ograniczenia walidacji

Pełny raport: ../SERVER/docs/inference/MULTI_ENGINE_CONTEXT_HOTFIX_PL.md. JAR, SHA-256, metadane i 16 wyników fixture: releases/2026-11-28-multi-engine-context/.

Testy integracyjne używają całego Spring Cognitive Pipeline oraz kontrolowanych źródeł i skryptowanych providerów; testy HTTP adaptera są osobne. Nie jest to pomiar produkcyjnego GPU ani ocena odpowiedzi prawdziwego modelu. Nie potwierdzono OCR ani jakości długiego kontekstu. Nie wykonano wdrożenia produkcyjnego.
