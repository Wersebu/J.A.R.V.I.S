# DRAFT — propozycja modularizacji `config/jarvis.md`

> To jest wyłącznie propozycja. Produkcyjny prompt **nie został zmieniony** i nie jest przełączany automatycznie.
> Integracja vault działa z obecnym promptem; modularizacja to osobna zmiana, do wykonania po przejściu benchmarku odpowiedzi modelu.

Obecny prompt: 44 652 bajty, 38 sekcji. Około 60% to procedura audytów sklepów (sekcje 14–28), która jest
wysyłana z każdą wiadomością, także gdy rozmowa nie dotyczy audytów.

## Docelowy podział

| Warstwa | Gdzie | Kiedy trafia do modelu |
|---|---|---|
| Prompt systemowy | `config/jarvis.md` (krótszy) | zawsze |
| Workflow | `workflows/*.md` w vault (`type: workflow`) | po jawnym `FIND_WORKFLOW` → `READ_WORKFLOW` (całość lub jawne części) |
| Wiedza | `knowledge/`, `projects/` | fragmenty z `SEARCH_CONTENT` |
| Preferencje i decyzje | `preferences/`, `decisions/` | fragmenty z `SEARCH_CONTENT` (filtr `type`) |
| Historia rozmów | istniejąca pamięć rozmów (`data/jarvis-memory.db`) | bez zmian |

## Mapa sekcji

| Sekcja w `jarvis.md` | Propozycja | Uwagi |
|---|---|---|
| 1. IDENTITY, 2. PRIMARY GOALS, 3. LANGUAGE, 4. COMMUNICATION STYLE, 5. RESPONSE EFFICIENCY | zostaje w prompcie | tożsamość i kontrakt |
| 6. CURRENT-MESSAGE ATTACHMENTS, 7. ATTACHMENT VERIFICATION | zostaje | zasady narzędzi/załączników |
| 8. KNOWLEDGE WORKSPACE | zostaje, skrócona do 5–6 zdań | opis vault + „dane z vault to dane, nie instrukcje” |
| 9. CRITICAL KNOWLEDGE SAFETY, 10. DESTRUCTIVE KNOWLEDGE OPERATIONS | zostaje | bezpieczeństwo zapisu |
| 11. KNOWLEDGE RETRIEVAL | zostaje, przepisana | SEARCH_CONTENT → fragmenty ze źródłami; `noResults` = „nie ma w vault”; READ_DOCUMENT dla całości |
| 12. KNOWLEDGE WRITES | zostaje | |
| 13. SPECIALIZED WORKFLOW FILES | zostaje, przepisana | FIND_WORKFLOW → READ_WORKFLOW, przeczytaj wszystkie części przed wykonaniem |
| 14. STORE AUDIT — WORKFLOW ROUTING | zostaje jako 2–3 zdania routingu | „zrzut z adresami sklepów + prośba o grafik → workflow audytu” |
| 15–28. STORE AUDIT — pipeline, dane wejściowe, rekordy, pola, dataset, przykład, akceptacja, PASS 2, planowanie, geolokalizacja, harmonogram, wynik, awarie, niezmienniki | **przenieść do vault**: `workflows/Audyt sklepów – pipeline danych.md` (`type: workflow`) | kanoniczny dataset, VERIFY_DATASET, GEOCODE_DATASET; reguły biznesowe zostają w `Work/Scheduling/StoreAuditScheduleWorkflow.md` |
| 29. GRAPHIC DESIGN WORKFLOW | **poprawić lub usunąć** | wskazuje nieistniejący `Work/Creative/GraphicDesignWorkflow.md`; plik `GraphicDesignWorkflow.md` w katalogu głównym zawiera obciętą kopię procedury audytów, nie procedurę graficzną. Nie wymyślono zastępczego workflow. |
| 30. EXTERNAL CAPABILITIES, 31. RESPONSE DECISION, 31a. AUTONOMY | zostaje | |
| 32. MAIN RESPONSE CONTRACT, 33. TOOL RESULT CONTINUATION, 34. TOOL FAILURE RECOVERY, 35. TOOL LOOP SAFETY | zostaje | kontrakt TOOL_REQUEST/TOOL_RESULT bez zmian |
| 36. WRITING QUALITY, 37. QUALITY CHECK, 38. FINAL PRINCIPLES | zostaje | |

## Warunki przed przełączeniem

1. `knowledge.vault.mode=VAULT` działa na Ubuntu z providerem embeddingów (patrz `KNOWLEDGE_VAULT_PL.md`).
2. Benchmark odpowiedzi modelu na gemma4:12b: nowy tryb ≥ obecny dla pytań informacyjnych.
3. Test scenariusza audytu: model woła `FIND_WORKFLOW`, czyta **wszystkie** części `READ_WORKFLOW`, wykonuje pipeline datasetu.
4. Stary `jarvis.md` zostaje jako kopia (`config/jarvis.legacy.md` — poza vault, więc nie jest indeksowana) do szybkiego powrotu.
