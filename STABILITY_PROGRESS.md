# Core — postęp stabilizacji

## Rozpoczęcie
- Przeczytano bieżące wymagania użytkownika i CORE_REQUIREMENTS.md Claude'a.
- Ochroniono dokumenty STABILITY_* Windows w katalogu nadrzędnym; dokumenty Core są w SERVER.
- Poprzedni audyt: SHARED/releases/2026-11-28-e2e-audit, 1150 testów (1146 wykonanych, 4 opt-in pominięte), rzeczywista lokalna Ollama: cztery workery i wynik częściowy. To historia, nie wyniki tego etapu.
- Znane braki: produkcyjny llama.cpp/16 GiB GPU i konta integracyjne niedostępne lokalnie; nie oznaczać testów produkcyjnych PASS.

## W toku / następne zadanie
Etap 1: uzyskać rzeczywistą serializowaną odpowiedź GET /api/v1/inference/state aktywnej Ollamy; sprawdzić kontrakt parameterSchema i wszystkie ścieżki inference.

Po każdym etapie dopisać zmiany, dowody, ograniczenia i następne zadanie. Nie powtarzać całego audytu po kompakcji.

## Etap 1 — audyt P0 (zmiany przygotowane)
- Kontrakt schematu jest płaski: parameterSchema.llamacpp.contextSize, bez wrappera properties. Windows ParameterEditor aktualnie obsługuje oba warianty; nie zmieniono kontraktu Core.
- Naprawiono agregację llama.cpp bez STRUCTURED_OUTPUT: natywna koperta odpowiedzi, buforowanie argumentów, walidacja przed publikacją. Dodano test wspólnej biblioteki, transportu HTTP llama i TaskResultDelivery.
- Startup warmup przypina konfigurację i sprawdza STALE_CONFIGURATION po admission; test wymusza zmianę konfiguracji i potwierdza zero żądań Ollamy.
- Moderacja pomijała aktywny silnik: dodano wybór aktywnego providera/konfiguracji i deadline, zachowując walidator wyniku i brak wykonywalnych narzędzi. Dodano test braku HTTP Ollamy przy aktywnym llama.
- Lista modeli odtwarza zapisane deklarowane możliwości; nie nazywa ich zweryfikowanymi. Test restartu profilu.
- WeatherTool dodaje legendę wyłącznie kodów obecnych w wynikach, według https://open-meteo.com/en/docs (51 = lekka mżawka). Test znanych/nieznanych kodów.
- Próba zastąpienia syntezy częściowej samym sklejaniem workerów została ODRZUCONA po regresji AuthorizationIndependentTasksTest; przywrócono syntezę głównego agenta. Zamiast niej wynik częściowy jest buforowany i sprawdzany pod kątem dopisania niepotwierdzonej giełdy (NASDAQ itd.). Test obejmuje brak jakiegokolwiek wycieku przed odrzuceniem. Nie usuwać testu zachowania prywatności workerów.
- CR-03: DefaultCognitiveEventBus jawnie odrzuca THINKING_TOKEN; to obecna ochrona prywatnego reasoning, nie błąd pola metadata.text. Nie usunięto jej. Przekazać Claude'owi rzeczywisty kontrakt granic/metadanych, nie obiecywać tokenów.

## Testy tego etapu
- stability-p0-tests.log: PASS dla pierwszej paczki poprawek (przed późniejszymi poprawkami moderacji/groundingu).
- stability-full-verify.log: pierwsza pełna próba FAIL (AuthorizationIndependentTasksTest ujawnił regresję sklejania workerów). Przyczyna usunięta; wymagana nowa regresja.
- stability-real-ollama.log: pierwsza rozszerzona próba rzeczywista W TOKU; uruchomiona przed korektą syntezy częściowej, więc nie jest finalnym dowodem artefaktu.

## Następny niezakończony krok
Uruchomić ponownie pełną regresję po zmianach TaskResultDelivery/AggregateEvidenceGuard i testach; sprawdzić live raport (GET state, wieloturowość, konta), wykonać finalną próbę live aktualnego kodu. Następnie zakończyć audyt istniejących modułów, sporządzić macierz 22 scenariuszy, build i dowody dla Claude.

## Etap 2 — routing, izolacja i pozostałe moduły
- Inwentaryzacja wywołań zapisana w test-tmp/stability-inference-calls.txt. Main/semantic/research/title/memory/reranker/coding korzystają z Brain/configurationId i aktywnego providera. Dreaming istnieje jako opt-in deterministyczna konsolidacja pamięci, bez własnego wywołania LLM. Samodzielny jarvis-planner jest interfejsem przyszłej funkcji, bez implementacji; istniejące planowanie odbywa się przez SemanticOrchestrator/DynamicPlanService.
- Knowledge Vault: dedykowane embeddingi CPU używają acquireCpuAuxiliary i num_gpu=0; embeddingi GPU Ollamy przy aktywnym llama.cpp są blokowane przed HTTP. Testy rezydencji w OllamaEmbeddingResidencyTest.
- Knowledge drafty miały brak izolacji właściciela (listing/approve/reject); przypisano właściciela do prywatnego PendingDraft i sprawdzenie przed usunięciem/wykonaniem. Test KnowledgeDraftOwnerIsolationTest. Nie zmieniono wspólnego katalogu wiedzy. Odtwarzanie wykonywalnych draftów po restarcie nie istnieje — nie dodawano nowej funkcji; pozostawiono pliki szkiców do przeglądu.
- Profile produkcyjne: gpuBudgetMiB zmieniono z niezmierzonego 14000 na 0 (fail closed). Profil pozostałych parametrów zgodny ze specyfikacją/benchmarkiem użytkownika; nie ma lokalnego pomiaru VRAM, więc aktywacja produkcyjna BLOCKED/ENVIRONMENT_LIMITATION.
- Uzupełniono API_CONTRACT.md i WINDOWS_REQUIREMENTS.md: płaski schemat, autoryzacja, granice reasoning, natywna koperta agregacji, przechowywanie profili, historyczne nazewnictwo health moderacji.
- Audyt wykazał drugi starszy format evidence (zwykły tekst) w istniejących task continuations. AggregateEvidenceGuard zachowuje go i buforuje przed publikacją; test nie pozwala dopisać nowej giełdy. Nie usunięto testów starszej orkiestracji.

## Aktualne dowody i stan wznowienia
- stability-real-final.log: PASS rozszerzonej próby rzeczywistej Ollamy po guardzie syntezy (nie obejmuje późniejszych niezależnych zmian knowledge). Raport test-tmp/e2e-audit/real-ingress-audit.json: cztery workery, dwie udane odpowiedzi/2 braki źródła, PARTIALLY_COMPLETED; brak wymyślonej giełdy. Powitanie, tożsamość, narzędzie/pogoda/USD, czat podczas workerów, CANCELLED, wieloturowy BURSZTYN-742, konta 404/403 i pusta obca lista; HTTP state zawiera schemat obu silników, brak sesji=401.
- stability-verified.log: FAIL dwóch testów starszych continuations przez zbyt restrykcyjne parsowanie evidence. Poprawione zachowaniem starszego formatu; nowy test regresji. Wcześniejszy log stability-full-final.log zawiera błąd kompilacji testu any() na przeciążonej metodzie; typ doprecyzowano.
- stability-final-regression.log: najnowsza pełna regresja W TOKU, session 7781. To jedyny aktualnie budowany katalog test-tmp/stability.

## Następny krok (obowiązuje zamiast starszej listy)
1. Sprawdzić stability-final-regression.log i naprawić ewentualne błędy; nie zmieniać Windows i nie uruchamiać Git.
2. Zakończyć raport modułów i macierz 22 scenariuszy: PASS dla wykonanych testów z zakresem, ENVIRONMENT_LIMITATION dla kont/oauth/bridge/audio/vision i prawdziwego llama.cpp, NOT_IMPLEMENTED tylko dla samodzielnego przyszłego planner-a / przyszłego Adaptive UI.
3. Zbudowany finalny JAR skopiować do NOWEGO SHARED/releases/2026-11-28-stability (nie nadpisywać e2e-audit); sprawdzić manifest i hash.
4. Na finalnym JAR wykonać test-tmp/restart-audit.py (izolowane zapisane ustawienia Ollamy, bez przeładowywania modelu). Zachować surowe state1/state2 i dowód parameterSchema.llamacpp dla Claude. Nie włączać produkcyjnego GGUF na lokalnej karcie 12 GiB.
5. Zaktualizować STABILITY_TEST_REPORT/ISSUES/PROGRESS/PLAN i WINDOWS_REQUIREMENTS oraz dołączyć dowody, ograniczenia i artefakt.

## Etap 3 — dodatkowa rzeczywista akceptacja wykryła P0 kontekstu
- stability-final-regression.log: PASS, 1165 testów, 1161 wykonanych, 4 opt-in pominięte; JAR f63c43... utworzony w SHARED/releases/2026-11-28-stability. UWAGA: to artefakt PRZED późniejszą poprawką kontekstu; nadpisać finalną wersją dopiero po weryfikacji.
- test-tmp/stability-ws-audit.py i StabilityWsProbe.java: rzeczywiste WS state.sync, reconnect/events.resume, reset_required po zmianie serverInstanceId i odtworzenie CANCELLED po restarcie PASS na wstępnym JAR. Pierwsza próba helpera miała kodowanie stdout CP1250; ustawiono UTF-8. Bez zmian produktu z tego powodu.
- test-tmp/stability-extended-audit.py: dokument rzeczywiście odczytany narzędziem knowledge.READ_DOCUMENT; kod 271828 i data 2031-02-03 poprawne. Weryfikator błędnie odrzucił nierozdzielający łącznik U+2011 w dacie — skorygować ocenę normalizacją formatu, nie treści.
- Rzeczywisty długi kontekst FAIL: druga tura 4184 znaków → HTTP 503, przyczyna CONTEXT_BUDGET_EXCEEDED w SemanticOrchestrator. Repro/log: test-tmp/stability-extended/f9dd65cd-4437-4f70-bbd0-491eebf7b670/boot-1.log. Model/provider nie był niedostępny, problemem był prompt.
- Dokładna przyczyna: context.prompt zawierał już pełną bieżącą wiadomość i historię. SemanticOrchestrator dodawał tę wiadomość ponownie w CURRENT_REQUEST oraz osobne referencje. Wszystko w głównej wiadomości systemowej było traktowane jako obowiązkowe. ToolCallingStage również przekazywał zduplikowany pełny prompt do native loop.
- Poprawka: PromptBuilder.buildRoutingPrompt / DefaultPromptBuilder zwraca wyłącznie tożsamość, osobowość, ustawienia, grounding/knowledge i załączniki, bez bieżącej wiadomości/historii. PromptBuilderStage zapisuje nativeSystemPrompt (wraz z provenance obrazów). Router i narzędzia używają tego systemu, bieżącej wiadomości osobno i ograniczonych referencji. Starszy buildPrompt pozostaje dla kompatybilności; pełna historia pozostaje w SQLite.
- Referencje rozmowy zachowują początek i koniec (400 znaków), aby nie tracić np. kodu z końca długiej wiadomości. Agregacja zapisuje/odtwarza tę ograniczoną historię; nie usuwa tożsamości i ustawień.
- Dodano RoutingContextBudgetRegressionTest: brak duplikacji bieżącej wiadomości/historii w systemie, zachowana osobowość, ograniczone referencje i zachowany fakt z końca najnowszej wiadomości.

## TERAZ WZNÓW TUTAJ
stability-context-final.log / session 24765: pełna regresja po poprawce P0 kontekstu W TOKU. Sprawdzić wyniki i ewentualne regresje. Po sukcesie ponownie spakować JAR z test-tmp/stability/javac-output-test, powtórzyć extended-audit (normalizować łączniki daty) na NOWYM artefakcie oraz WS/restart/GET state. Nie dostarczać starego f63c43... jako zakończonego Stability Update.
Wciąż trzeba ukończyć STABILITY_TEST_REPORT (macierz 22 + moduły), końcowe statusy ISSUES/PLAN/PROGRESS i dowody dla Claude. Nie restartować całego audytu ani produkcyjnych integracji.

## Dodatkowy P0 po finalnej próbie realnej
- stability-real-release.log: FAIL, plan semantyczny pominął BTC i CSS (utworzył tylko dwa workery). Nie ukryto asercji czterech workerów ani nie przedstawiono tego jako PASS.
- Dodano ochronę kompletności jawnych identyfikatorów w wieloelementowym planie (ogólne tokeny, nie lista słów decydujących o routingu). Brak np. CSS wymusza istniejącą ograniczoną naprawę decyzji przed uruchomieniem jakiegokolwiek workera. Dwa nowe testy: odrzucenie pominięcia i naprawa bez przedwczesnego admission. Nie ma dodatkowego wywołania dla poprawnych/prostych decyzji.
- W toku: pełne verify+JAR stability-coverage-verified.log / session94451 oraz rzeczywista akceptacja stability-real-coverage.log / session34500. Poprzedni SHA26cea obejmuje wszystkie wcześniejsze poprawki, ale NIE tę ostatnią; dostarczyć dopiero nowy hash po build.

## Bieżąca końcówka — dokument ze źródła serwerowego
- stability-coverage-verified.log PASS: 1172/0fail/0error/4skip, JAR19fde8e6a461a85a4bf20333732e5eb73cfa60aa6cf4e59d79d6a760ede3f5e8. stability-real-coverage.log PASS: cztery workery, dwie udane odpowiedzi pogoda/USD i jawne braki SearXNG.
- Retest tego JAR: wszystkie długie tury i recall PASS, ale dokument FAIL: model wybrał browser zamiast knowledge, prawidłowo odnotował brak Windows zamiast sfabrykować odczyt. Dowód test-tmp/stability-extended/0a4cdda0-124a-49c3-bbf1-d92a76c3c817/extended-audit.json.
- Dodano ogólną instrukcję ownership źródeł: Knowledge Vault jest na serwerze, jego odczyt nie wymaga przeglądarki/Coding Bridge; Windows do rzeczywistych żądań PC, web do internetu. Pełna regresja/build stability-final-source-verified.log, session23096 w toku.
- WS/restart i fault na JAR19fde... PASS; memory-audit w session13855 kończy ostatnią próbę. Kolejne kroki: nowy JAR po verify, powtórzyć extended/document, odnotować rzeczywisty hash każdego dowodu i zakończyć dokumentację bez fałszywego przypisywania starszych prób nowemu plikowi.

## KOŃCOWY PUNKT WZNOWIENIA — zastępuje wcześniejsze sesje
- Finalny JAR SHA256 a27bdd551c3c33eafc0179f951c3a11746a2d1f0681db08ab725acf8af78188c zbudowany i sprawdzony. Pełna regresja + celowana ostatnia paczka: 1172 raportowanych testów / 1168 wykonanych / 4 skip / zero failures/errors. Logi stability-final-source-verified.log i stability-artifact-verified.log.
- Finalny JAR: extended-audit PASS (dokument 271828/data, 4 długie tury 4184 znaków + recall STAB-LONG-4 przy 8192, peak input3276), memory-audit PASS (rzeczywista ekstrakcja i restart/izolacja), ws-restart-audit VERIFIED_LOCAL (resume/reset/snapshot/CANCELLED), fault-audit PASS (422/503/SSE ERROR, brak assistant i busy=false).
- RealIngressAuditTest powtórzony po zmianie nativeSystemPrompt; wynik i końcowy zakres zapisane w wydaniu oraz stability-real-final-source.log.
- Uzupełniono 22 scenariusze i audyt modułów. Produkcyjny llama/GPU/pełne integracje pozostają ENVIRONMENT_LIMITATION/BLOCKED; nie ogłaszać pełnej produkcyjnej akceptacji. Pamięć w głównym promptcie NOT_IMPLEMENTED; dowolny środkowy fakt starej długiej historii NOT_VERIFIED.
- Bez Git, edycji Windows i wdrażania. Następna praca wymaga dostępnego docelowego środowiska; zachować wszystkie zabezpieczenia, a gpuBudgetMiB wypełnić dopiero po realnym pomiarze.

## ZAMKNIĘCIE LOKALNEGO ETAPU
Końcowe pełne verify stability-final-source-verified.log PASS: 1172 testy / 1168 wykonanych / 4 skip / zero failures/errors. RealIngressAuditTest stability-real-final-source.log PASS: cztery workery, częściowa odpowiedź przy niedostępnym SearXNG, auth i recall. Finalny JAR SHA256 a27bdd551c3c33eafc0179f951c3a11746a2d1f0681db08ab725acf8af78188c. Dokładny finalny JAR: dokument oraz wszystkie długie tury/recall PASS, peak per-call 3257/8192; surowe SSE i metryki w wydaniu. WS/awarie/pamięć PASS na poprzednim artefakcie po guardzie; dokładna proweniencja w evidence-provenance.json. Wszystkie możliwe lokalne etapy zakończone. Otwarte granice: produkcyjny llama/VRAM/przełączenie, autoryzowane konta, vision/audio/bridge, pamięć prompt NOT_IMPLEMENTED, dowolny środkowy fakt starej historii NOT_VERIFIED. Następne wznowienie: otwarte pozycje raportu w odpowiednim środowisku; nie powtarzać zamkniętego audytu.
