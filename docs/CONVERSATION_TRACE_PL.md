# Ślad rozmowy (diagnostyka)

Każde zapytanie dopisuje czytelny blok do pliku jednej rozmowy:

```
data/traces/<conversationId>.md        (względem katalogu roboczego Core, np. /opt/jarvis)
```

Blok zawiera:

- godzinę, `requestId` i Twoją wiadomość,
- wiedzę przekazaną modelowi (np. `SOURCE_ADDED workflows/Spotify.md` z automatycznego ładowania),
- **MYŚLENIE (x s)**: cały tekst myślenia modelu, osobno dla każdego wywołania modelu, z czasem trwania,
- decyzje (`MAIN_MODEL_ACTION`, `TOOL_REQUEST_DETECTED`, wznowienia pętli),
- **NARZĘDZIE (x s)**: nazwa, argumenty, wynik (OK/BŁĄD, komunikat, podgląd danych do 1500 znaków).
  Kroki wewnątrz narzędzi złożonych (`pc__spotify_play`, `pc__spotify_control`) są pokazane jako `↳ krok`,
- **ODPOWIEDŹ**: tekst wysłany użytkownikowi,
- **podsumowanie**: całość, myślenie modelu, narzędzia, strumień odpowiedzi i reszta (pipeline, przygotowanie promptu).

Kolumna „czas” to sekundy od przyjęcia wiadomości, więc przerwy między wierszami pokazują, gdzie ucieka czas.

## Jak przesłać ślad

Po kilku testach w jednym wątku:

```bash
ls -t /opt/jarvis/data/traces | head -3          # najnowszy plik = ostatnia rozmowa
cat /opt/jarvis/data/traces/<conversationId>.md
```

Skopiuj całość i wklej. Plik jest w Markdown, więc czyta się go też w Obsidianie albo VS Code.

## Ustawienia

| Zmienna | Domyślnie | Znaczenie |
|---|---|---|
| `JARVIS_CONVERSATION_TRACE_ENABLED` | `true` | wyłączenie: `false` |
| `JARVIS_CONVERSATION_TRACE_DIR` | `data/traces` | katalog plików |

Pola wyglądające na sekrety (token, hasło, klucz API, cookie) są zastępowane `[redacted]`. Ślad zawiera
jednak argumenty i wyniki narzędzi, więc nie publikuj go w całości. Usuwanie starych plików jest ręczne.
