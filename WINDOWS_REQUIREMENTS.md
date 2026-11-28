# Core → Claude: wymagania i dowody Windows

Nie edytowano projektu Windows. Dokumenty Claude'a w katalogu głównym zachowano.

## CR-05 — parameterSchema
Trwa weryfikacja rzeczywistego GET /api/v1/inference/state. Nie zmieniać kontraktu na podstawie samego komunikatu UI. Endpoint wymaga uwierzytelnionego aktywnego ADMIN; 401/403 nie jest pustym schematem. Dowód serializacji zostanie dołączony po próbie.

## Dotychczasowe kontrakty
- SHARED/E2E_REGRESSION_CONTRACT_FOR_CLAUDE.md: metryki, korelacja zdarzeń, wynik częściowy, schemat wyboru.
- Surowe prywatne reasoning nie jest odpowiedzią użytkownika. Granice i metadane rzeczywistych zdarzeń nie oznaczają dostępności publicznych tokenów reasoning. Nie symulować brakujących delt.
- Core pozostaje źródłem prawdy; nie ukrywać źródłowego problemu przez globalne filtrowanie JSON.

## Adaptive UI Engine
Tylko koncepcja: ../FUTURE_ADAPTIVE_UI.md. Bez nowych komponentów/implementacji w Stability Update.

## Potwierdzony kształt schematu (rzeczywiste REST)
Pierwsza rozszerzona próba z prawdziwą Ollamą: ADMIN otrzymał oba klucze silników i `parameterSchema.llamacpp.contextSize.minimum`; bez sesji HTTP 401, zwykły USER HTTP 403. Kontroler nie filtruje schematu do aktywnego silnika. Kształt jest płaski, np. `parameterSchema.llamacpp.contextSize = {type:integer, minimum:512, maximum:32768, default:4096, requiresReload:true}`; `parallelSlots` ma zakres 1..16. Nie ma wymaganego wrappera `properties`. Sprawdzono wyłącznie odczytem, że bieżący Windows ParameterEditor toleruje oba kształty. Nie zmieniono kodu Windows. Surowy dowód końcowego JAR zostanie dołączony do wydania.

## Powody pustego edytora do sprawdzenia po stronie klienta
- Zachować HTTP status i treść błędu 401/403, zamiast traktować je jako pusty InferenceState.
- Pobierać schemat dla edytowanego silnika llama.cpp, a nie silnika aktualnie aktywnego.
- Obsługiwać płaską mapę deskryptorów i enum bez type; nie uzależniać edytora od listy już skonfigurowanych modeli.
- `configurationStatus=REQUIRED/SAVED` opisuje zapis profilu, `status=AVAILABLE/INVALID` wykrycie pliku, a gotowość działania wynika z globalnego state.status i selection. declaredCapabilities nie oznacza verifiedCapabilities.
- Osobny zapis profilu nieaktywnego bez uruchamiania nie ma endpointu (NOT_IMPLEMENTED). Istniejący kontrakt przyjmuje komplet parametrów i aktywuje model; UI może trzymać szkic lokalnie. Pusty parameters odtwarza zapisany profil po wcześniejszej udanej aktywacji, niepusty zastępuje pełen profil.

## CR-03: rzeczywista przyczyna braku tokenów reasoning
DefaultCognitiveEventBus jawnie pomija THINKING_TOKEN niezależnie od poprawności metadata.text. Zachowano tę granicę prywatności zgodnie z wymaganiem nieusuwania zabezpieczeń. Publiczny kanał przekazuje granice i metadane rzeczywistego reasoning; nie ma gwarancji publicznych surowych delt. Windows nie powinien symulować tekstu ani uważać samego timera za dostępny tekst reasoning.

## Brak zmian łamiących kontrakt
Protokół pozostaje 1.9.0. Istniejące assistant.message.* i system.metrics pozostają źródłem prawdy. Native koperta agregacji jest wewnętrzna, nie pokazuj jej argumentów jako odpowiedzi. Przy niepełnym materiale Core może publikować odpowiedź dopiero po końcowej walidacji; koordynator nadal jest wtedy aktywny.

## Uzasadniona korekta semantyki błędu REST
Błąd `CONTEXT_BUDGET_EXCEEDED` nie oznacza niedostępnego providera. Normalny REST czatu zwraca dla niego HTTP 422 z istniejącymi polami ErrorResponse: status/error/message/timestamp; message zaczyna się od stabilnego kodu. Wcześniej był myląco mapowany do 503 „AI provider is unavailable”. Nie pokazuj tego jako awarii połączenia ani nie ponawiaj automatycznie identycznego żądania. Inne awarie providera zachowują 503 i bezpieczną wiadomość. To celowana zmiana semantyki statusu, bez nowego kształtu DTO i bez wyświetlania prywatnego promptu. SSE zachowuje istniejące zdarzenia błędów.

## Dowody finalnego wydania
SHARED/releases/2026-11-28-stability/inference-state-http.json zawiera rzeczywisty state finalnego JAR, płaską mapę 24 pól llama przy aktywnej Ollamie i dowody 200/401/403. Pozostałe pliki JSON pokazują WS resume/reset_required, utrzymanie CANCELLED po restarcie, błędy 422/503 i rzeczywistą telemetrię. Nie zmieniono UI ani wersji protokołu 1.9.0. Nie traktować sumy inputów jako zajęcia kontekstu i nie ponawiać bez zmian żądania odrzuconego kodem CONTEXT_BUDGET_EXCEEDED.

Dokumenty Knowledge Vault są po stronie serwera; odczyt przez knowledge nie zależy od połączenia Windows Browser/Coding Bridge. Końcowe pomiary i hash: build-verification.json/performance-summary.json. W evidence-provenance.json rozdzielono dokładne artefakty prób; starsze WS/awarie/pamięć nie są przedstawiane jako ponowne wykonanie na innym pliku JAR.
