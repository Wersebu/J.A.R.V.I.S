# Jarvis jako agent — szybki start

Jak skonfigurować Jarvisa, żeby pracował jak agent: planował, czytał i edytował pliki na Twoim PC, uruchamiał polecenia, pracował z GitHubem.

## 1. Serwer (Core)

- **Model.** To on decyduje o jakości pracy agentowej. Wymagane jest natywne wywoływanie narzędzi (tool calling). Sprawdzone rodziny w Ollamie: `gpt-oss:20b` (domyślny), `qwen3` / `qwen3-coder` (dobry do kodu), `hermes3` (Nous Research, dobre function calling). Większy model lepiej planuje i rzadziej się gubi. Najlepiej porównać 2–3 modele na tym samym zadaniu (zmiana modelu jest w aplikacji).
- **Budżety** w `application.yml` (`jarvis.tools`):
  - `max-calls-agent: 60` — liczba kroków dla zadań z folderem roboczym lub planem;
  - `max-result-chars: 16000` — ile wyniku narzędzia trafia do modelu w całości;
  - `history-char-budget: 120000` — budżet kontekstu pętli.

  Jeśli model ma duże okno kontekstu (`num_ctx`), oba ostatnie limity można zwiększyć.
- **nginx** (jeśli Core jest za proxy): ustaw `proxy_read_timeout 3600s;` dla `/ws/` i `/api/v1/chat/stream`.

## 2. PC (aplikacja Windows)

1. Skopiuj `config/pc-access.example.yml` jako `config/pc-access.yml` i wpisz foldery, do których Jarvis ma dostęp (domyślnie tylko folder użytkownika).
2. W czacie kliknij **📁 Wybierz folder roboczy**. Od tej pory w tej rozmowie:
   - ścieżki względne i terminal startują w tym folderze;
   - Jarvis czyta `JARVIS.md` (albo `AGENTS.md` / `CLAUDE.md`) z tego folderu i stosuje się do niego.
3. Zainstaluj na PC to, czego Jarvis ma używać: `git`, [`gh`](https://cli.github.com/) (GitHub CLI, potem `gh auth login`), JDK/Maven, Node, Python…

Przykładowy `JARVIS.md` w projekcie:

```markdown
# Projekt: sklep
- Build: mvn -q package ; testy: mvn -q test
- Kod w src/main/java, testy w src/test/java; nie ruszaj generated/
- Commity po polsku, krótko. Nowa funkcja = nowa gałąź feature/<nazwa>.
```

## 3. Jak z nim pracować

- Pisz cel, nie instrukcje krok po kroku: *„dodaj walidację e‑maila w formularzu rejestracji, uruchom testy i popraw, jeśli coś padnie”*.
- Jarvis tworzy plan i pracuje sam. Pasek nad polem czatu pokazuje, co robi; po najechaniu widać cały plan.
- Ryzykowne operacje (git push, usuwanie, reset --hard) wyświetlają okno zgody na PC.
- Gdy aplikacja jest zminimalizowana, w rogu ekranu jest małe okienko statusu, a po zakończeniu przychodzi powiadomienie.
- Zerwane połączenie nie przerywa pracy — aplikacja sama się wznawia.
- „Kontynuuj” wznawia niedokończony plan. „Cofnij zmiany” → Jarvis używa `pc__undo`.

## 4. GitHub

- Lokalnie przez `git` i `gh` w terminalu Jarvisa: gałęzie, commity, `gh pr create`, `gh issue list`… Push i usuwanie wymagają zgody.
- Zdalnie przez GitHub MCP (issues/PR bez lokalnego klonu): ustaw `GITHUB_TOKEN` i `JARVIS_MCP_GITHUB_ENABLED=true` na serwerze (szczegóły w `docs/MCP.md`).

## 5. „Wizualny mózg”, Obsidian i Hermes

- **Obsidian** (nie Anthropic) to aplikacja do notatek w Markdown z widokiem grafu. Folder wiedzy Jarvisa (`knowledge/`) to zwykłe pliki `.md`, więc można go otworzyć w Obsidianie jako vault i oglądać graf. Aplikacja Jarvisa ma też własny „żywy” graf wiedzy.
- **Hermes** to nazwa dwóch rzeczy od Nous Research:
  - modeli `hermes3` / `hermes4`, które można pobrać do Ollamy i wybrać jako mózg Jarvisa;
  - osobnego frameworka agentowego („Hermes Agent”).

  Drugi agent obok Jarvisa dublowałby to, co Jarvis już robi (pętla narzędzi, plan, pamięć, MCP). Rozsądniej jest przetestować model Hermes w Jarvisie i zostawić ten, który najlepiej radzi sobie z narzędziami.
