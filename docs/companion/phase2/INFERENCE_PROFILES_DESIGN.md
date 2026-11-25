# Projekt profili i limitów kontekstu świadomych modelu

> Implementation follow-up: PHASE2_IMPLEMENTATION.md and PHASE2_CORE_COORDINATION.md. The latest operator-qualified physical target is 24576; earlier numbers below are historical design/audit evidence.


Status: projekt do implementacji kolejnego etapu, 2026-11-25; brak zmiany produkcyjnych parametrów. Audyt `a0cc3c7`.

## Niezgodność 262144 / 131072

Produkcyjny Core: context-window=262144, reserved-output-tokens=30000. Obecny `AiContextProperties.maxInputTokens()` daje **232144**. Model GPT-OSS deklaruje **131072**. Przy tej samej rezerwie realny górny budżet wejścia wynosi najwyżej **101072**, jeszcze przed odjęciem narzutu szablonu, schematów narzędzi, historii, obrazów i marginesu estymatora. Różnica 131072 tokenów może skutkować obcięciem danych przez runner lub odrzuceniem requestu. Rezerwa 30000 nie jest `num_predict` i nie ogranicza długości rozumowania/generowania.

Core obecnie nie waliduje kontekstu z metadanymi modelu. `DefaultActiveModelService.showCapabilities()` czyta z `/api/show` tylko capabilities. Znajomość nazwy `gpt-oss:20b` i konfiguracji administratora nie wystarcza: tag może wskazać inny digest. Nie wolno interpretować wartości 131072 jako dowodu, że tyle kontekstu mieści się wraz z Whisperem i wieloma slotami w GPU.

## ModelContextLimits — kontrakt projektu

`ModelMetadataResolver` pobiera `/api/show {model}` i `/api/tags`, wiąże wynik z endpointem, digestem i architekturą. Odczytuje `model_info.general.architecture`, następnie **dokładny** klucz `<architecture>.context_length` (dla GPT-OSS należy potwierdzić rzeczywistą architekturę z odpowiedzi, nie zgadywać prefiksu po nazwie tagu). Waliduje dodatnią całkowitą wartość; nie bierze największego przypadkowego `*.context_length`. Cache TTL 5 minut, single-flight, invalidacja po zmianie digestu/modelu/endpointu. Pobranie metadanych ma osobny krótki timeout; nie zajmuje permitu inferencji.

Niezmienne obiekty:

```text
ModelContextLimits(endpoint, model, digest, architecture,
  declaredModelMax, verifiedServingMax?, metadataSource, fetchedAt, status)
InferenceProfile(model, contextWindow, maxGeneratedTokens, reasoning,
  priority, queueTimeout, executionTimeout, resourceClass, runnerPool)
ResolvedInferencePlan(profileId, modelDigest, requestedContext,
  modelMax, servingMax?, deploymentCap, physicalRunnerContext,
  promptBudget, outputBudget, safetyMargin, limitReasons, configRevision)
```

`/api/ps.context_length` jest **obserwacją aktualnie załadowanego runnera**, nie maksymalną zdolnością modelu i nie trwałym serving cap. Może być mniejsze od dopuszczalnego kontekstu. Nie używać go do niejawnego zmieniania runnera co turę. `verifiedServingMax` to osobny, zatwierdzony limit endpointu/digestu wynikający z kompatybilności runtime; bez dowodu pozostaje null.

Algorytm przed wysłaniem jakiegokolwiek generowania:

1. Rozwiąż konkretny model i digest; zrób snapshot profilu i metadanych na całe wywołanie. Nie mieszaj limitów nowego modelu ze starym `Brain` w trakcie switch.
2. `allowedMax = min(declaredModelMax, deploymentCap, verifiedServingMax jeśli znany)`. Konfiguracja nie może podnieść limitu modelu.
3. `physicalRunnerContext` pochodzi ze stabilnej puli, musi być `<= allowedMax`. Przy konfiguracji 262144 i modelMax=131072 tryb normalny emituje ostrzeżenie `MODEL_CONTEXT_CLAMPED`, a wyliczony plan nigdy nie przekracza 131072. Tryb strict odrzuca niezgodną konfigurację przed aktywacją; switch atomowo zostawia poprzedni poprawny model.
4. `logicalContext = min(profile.contextWindow, physicalRunnerContext)`; `outputBudget = profile.maxGeneratedTokens`, dodatni i mniejszy od logicalContext minus margin. Nie obniżać po cichu żądanego wyjścia do 0: błędny profil odrzucić.
5. `promptBudget = logicalContext - outputBudget - safetyMargin`. Domyślny margin startowy max(256, 5% logicalContext), do kalibracji. Policz **cały serializowany input**: system, historia, tool schemas, tool results, bieżąca wiadomość, template i multimodal overhead. Licznik modelowy jeśli dostępny; estymator znakowy ma status ESTIMATED, nie gwarancję dopasowania. Użyj większego marginesu dla języków/kodu/obrazów, dla których brak walidowanego estymatora.
6. Redukuj strukturalnie: zredukuj stare wyniki narzędzi/historię, zachowaj system, ostatnie polecenie i pary tool_call/tool_result. Jeżeli nie da się zmieścić obowiązkowych elementów, zwróć `CONTEXT_BUDGET_EXCEEDED` przed HTTP. Nie przecinaj JSON schemas ani bloków wywołań narzędzi po liczbie znaków.
7. Wyślij stały `options.num_ctx=physicalRunnerContext`, `options.num_predict=outputBudget` oraz obsługiwane `think`. Egzekwuj deadline obejmujący kolejkę i odczyt body streamu. Zapisz resolved plan w diagnostyce; brak prywatnych promptów.

Nieznane metadane: użyj ostatnich zweryfikowanych danych **tego samego digestu** z ograniczonym okresem ważności, albo zatwierdzonego override limitu przypisanego do digestu. Bez tych danych w trybie produkcyjnym odmów aktywacji nowego modelu (`MODEL_LIMIT_UNKNOWN`); nie przyjmuj arbitralnego 262144 ani rzekomo bezpiecznego 4096 jako dowodu wsparcia. Tryb diagnostyczny może użyć jawnego niezweryfikowanego limitu, ale musi go oznaczyć i nie kwalifikuje konfiguracji do produkcji.

## Profile startowe

Wszystkie mogą używać `gpt-oss:20b`; żadna osobna nazwa modelu nie jest wymagana. Liczby są hipotezami do benchmarku, nie ustawieniami produkcyjnymi. Mniejszy numer priorytetu oznacza wyższy priorytet. Context to **logiczny** limit operacji; fizyczny num_ctx wspólny dla puli, np. 16384 dopiero po kwalifikacji sprzętu.

| Profil | Model | Context | Max output | Reasoning | Priorytet | Queue / wykonanie | Klasa |
|---|---|---:|---:|---|---:|---|---|
| Main Conversation | gpt-oss:20b | 16384 | 2048 | medium | 10 | 15s / 180s | INTERACTIVE |
| Conversation Title | gpt-oss:20b | 4096 | 128 | low | 70 | 5s / 20s | OPTIONAL_SMALL |
| Conversation Acknowledgment | gpt-oss:20b | 4096 | 64 | low | 20 | 1s / 5s | EVENT_TEXT |
| Progress Announcement | gpt-oss:20b | 4096 | 96 | low | 60 | 2s / 10s | EVENT_TEXT |
| Task Planning | gpt-oss:20b | 8192 | 1024 | medium | 40 | 60s / 120s | BACKGROUND |
| Research Agent | gpt-oss:20b | 16384 | 2048 | medium | 50 | 120s / 180s | BACKGROUND_LARGE |
| Coding Agent | gpt-oss:20b | 16384 | 4096 | high | 50 | 120s / 300s | BACKGROUND_LARGE |
| Memory Extraction | gpt-oss:20b | 8192 | 768 | low | 80 | 30s / 60s | OPTIONAL_SMALL |
| Dreaming | gpt-oss:20b | 8192 | 768 | low | 90 | 1s / 60s | IDLE_ONLY |
| Tool Selection | gpt-oss:20b | 8192 | 512 | low | 10* | 15s / 60s | INHERIT |
| Tool Result Processing | gpt-oss:20b | 16384 | 2048 | medium | 10* | 30s / 180s | INHERIT |

`*` Dziedzicz foreground/background z zadania nadrzędnego. Nie awansować selekcji narzędzia agenta tła do interaktywnej klasy. Profile narzędzi nie oznaczają obowiązkowych dodatkowych wywołań: istniejące rundy modelu przyjmują odpowiedni profil. Łączny task deadline ogranicza wszystkie rundy, nie odnawia się przy każdej.

ACK i progress domyślnie **deterministyczne** na podstawie task.created/started/stage/completed, z lokalizowanymi szablonami. Nie wysyłać LLM tylko po „przyjęto zadanie”. Tytuł: jeden ograniczony best-effort call, fallback z pierwszej wiadomości; można wybrać całkowicie deterministyczny tryb. Dreaming obecnie nie używa LLM — profil jest rezerwą dla przyszłego opt-in rozszerzenia.

Przykład przyszłej konfiguracji (schemat projektowy, obecny binder go nie obsługuje):

```yaml
jarvis:
  inference:
    metadata-policy: verified-required
    context-mismatch-policy: clamp-and-report
    runner-pools:
      primary:
        model: gpt-oss:20b
        physical-context-window: 16384
        deployment-context-cap: 32768
    profiles:
      conversation-title:
        model: gpt-oss:20b
        runner-pool: primary
        context-window: 4096
        max-generated-tokens: 128
        reasoning: low
        priority: 70
        queue-timeout: 5s
        execution-timeout: 20s
        resource-class: OPTIONAL_SMALL
```

Jeżeli profil wymaga większego context niż pula, jawnie odrzuć lub skieruj do zatwierdzonej innej puli/endpointu. Nie twórz automatycznie kopii modelu w GPU. Zmiana modelu w profilu jest możliwa, ale admission musi uwzględnić koszt nowego modelu i potencjalne eviction.

## Dlaczego nie zmieniać num_ctx per operacja

W [kodzie scheduler v0.33.2](https://github.com/ollama/ollama/blob/v0.33.2/server/sched.go) `effectiveContext` ogranicza kontekst do limitu treningowego, `needsReload` porównuje efektywne opcje runnera. Zmiana jawnego num_ctx może wymusić reload; stabilne 262144 i 131072 mogą po clamp dać ten sam kontekst. Scheduler uwzględnia liczbę slotów przy wielkości kontekstu. To analiza kodu, nie obserwacja produkcyjnych reloadów. Potwierdzić ją naprzemiennym testem 4096/8192 oraz logami i PID runnera. `load_duration` samo nie dowodzi restartu.

Dynamiczny jest budżet promptu, generowania, reasoning, priorytet i deadline. Fizyczny kontekst stały w danym okresie działania puli. Zmiany puli tylko po drain i świadomej operacji administratora; bez automatycznego oscylowania w rytm tytuł/chat.

## Implementacja i akceptacja

Nowe klasy: ModelMetadataResolver/ModelContextLimits/InferenceProfileRegistry/ResolvedInferencePlan w common+ollama, binder z walidacją; rozbudowa DefaultActiveModelService o digest/limit i atomowy switch. ContextBudgetService musi korzystać z konkretnego planu i obsługiwać zarówno prompt generate, jak pełne messages+tools chat. OllamaProvider: opcje, deadline streamu, log planu; gateway: rezerwacja policzona dla planu. Call sites z tabeli otrzymują profile; Brain nie powinien być jedynym źródłem parametrów sprzętowych. Windows może wyświetlić reason ograniczenia z telemetrii; bez własnego wyliczania limitu.

Testy: 262144/131072/30000 → max 101072 przed marginesem; 8192 pool daje jeszcze mniejszy budżet; zmiana digestu pod tym samym tagiem; brak/zerowe/ujemne/błędne metadane; inna architektura; timeout `/show`; cache/single-flight; strict/clamp; output+margin>=context odrzucone; pełna historia i duże schemas; zachowanie par tool; multimodal unknown overhead; stabilny num_ctx dla kolejnych profili; num_predict i think serializowane; profile tła nie awansują; podmiana modelu w trakcie tury. Osobno test runtime braku reloadów przy przełączaniu logicznych profili.
