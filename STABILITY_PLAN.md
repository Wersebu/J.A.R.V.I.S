# Core Autonomous Stability Update

Stan wznowienia: czytaj ten plik, STABILITY_PROGRESS.md, STABILITY_ISSUES.md i STABILITY_TEST_REPORT.md. Dokumenty o tych nazwach w katalogu nadrzędnym należą do Claude/Windows; nie nadpisywać ich.

Zakres: istniejące moduły Core. Bez Git, edycji Windows, wdrożeń, destrukcyjnych operacji produkcyjnych i obchodzenia zabezpieczeń. Dane testów izolowane w test-tmp; Java 21. Statusy: PASS, FAIL, BLOCKED, NOT_VERIFIED, NOT_IMPLEMENTED, ENVIRONMENT_LIMITATION. PASS testu protokołu nie oznacza PASS produkcji.

1. P0 inference: rzeczywiste GET state, schemat obu silników, zapis/odtworzenie profili, wybór, rollback, zasoby i możliwości.
2. P0 routing: zinwentaryzować wszystkich konsumentów AIProvider/BrainRouter, naprawić ścieżki pomijające aktywną konfigurację; zachować STALE_CONFIGURATION.
3. P0 orkiestracja, streaming, context i lifecycle: wykorzystać istniejące testy i poprzedni audyt, uzupełnić rzeczywiste wejście/awarie/reconnect.
4. Audyt pozostałych modułów: osobowość, pamięć/dreaming, knowledge, historia/projekty, plany, voice, vision/załączniki, research, integracje, MCP/Roblox/Windows Bridge/coding, auth/moderation/diagnostyka/zasoby/trwałość. Nie implementować brakujących funkcji.
5. Akceptacja: macierz 22 scenariuszy na obu silnikach; testy rzeczywiste tam, gdzie dostępne środowisko. Braki środowiska opisywać oddzielnie i kontynuować niezależne etapy.
6. Końcowa pełna regresja, nowy produkcyjny JAR, hash, dowody API i WINDOWS_REQUIREMENTS.md.

Adaptive UI Engine: wyłącznie dokumentacja istniejącego pomysłu z ../FUTURE_ADAPTIVE_UI.md. Ustrukturyzowany JSON, wykresy/checklisty/diagramy/formularze/karty/panele z bezpiecznego katalogu, integracja z istniejącym WS/Windows. Bez implementacji w tym zadaniu.

## Stan końcowy lokalnego etapu
Etapy 1–6 wykonane w dostępnym środowisku; zakresy i 22 statusy w STABILITY_TEST_REPORT.md. Nowy JAR i dowody są w SHARED/releases/2026-11-28-stability. Pozostają próby produkcyjnego llama/VRAM/przełączenia, kont integracyjnych, vision/audio/bridge oraz ograniczenia opisane w ISSUES. Nie wdrażać ani aktywować niezmierzonego profilu. Następne wznowienie: sprawdzić wyłącznie otwarte ograniczenia w docelowym środowisku, nie powtarzać ukończonych analiz.
