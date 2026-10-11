# Kalendarz Google

Jarvis czyta Twój kalendarz Google (plan dnia, wolne terminy), może **dodawać i przesuwać** wydarzenia, ale **nie usuwa** ich. Każde dodanie lub zmiana wymaga Twojej zgody w oknie na PC. Model nie może tego pominąć, nawet gdyby „poprosiła” go o to treść maila lub zaproszenia.

Kalendarz działa jak Gmail: połączenie jest w Core, osobne dla każdego konta Jarvisa. Używa tej samej aplikacji Google i tego samego pliku klienta na serwerze, ale ma własną zgodę i własny token. Możesz więc mieć połączony Gmail bez kalendarza i odwrotnie.

## Jednorazowa konfiguracja

Jeśli Gmail już działa, zostały tylko dwa kroki.

### 1. Włącz Google Calendar API

Link: https://console.cloud.google.com/apis/library/calendar-json.googleapis.com

- Sprawdź, czy u góry wybrany jest projekt **Jarvis** (ten sam co dla Gmaila).
- Kliknij **Włącz** (*Enable*). Jeśli widzisz **Zarządzaj** (*Manage*), API jest już włączone.

Na serwerze nie zmieniasz nic. Plik klienta (`JARVIS_GMAIL_CLIENT_FILE`) i klucz szyfrowania są wspólne.

### 2. Wgraj nowe wersje i połącz kalendarz

1. Przebuduj i zrestartuj Core oraz aplikację Windows.
2. Skopiuj do vault na serwerze (`/opt/jarvis/knowledge/workflows/`):
   - nowy plik `docs/vault/workflows/Kalendarz.md`,
   - zaktualizowany `docs/vault/workflows/Gmail.md` (wersja 5, z przejściem „z poczty do kalendarza”).
3. Powiedz Jarvisowi: **„Połącz mój kalendarz Google”**. Na PC otworzy się strona Google:
   - wybierz konto, potem **Zaawansowane → Przejdź do: Jarvis**,
   - **zaznacz oba pola** uprawnień: wyświetlanie kalendarzy oraz wyświetlanie i edytowanie wydarzeń,
   - kliknij **Dalej**. Jarvis odpowie, które konto połączył.

Google pokaże uprawnienie do „edytowania wydarzeń”, bo bez niego nie da się nic dodać. Usuwania Jarvis i tak nie ma: tej operacji po prostu nie ma w narzędziach.

## Przykładowe polecenia

- „Co mam dzisiaj?”, „co mam jutro?”, „plan na ten tydzień”
- „Kiedy mam wolną godzinę w piątek?” → wolne okna między 8:00 a 20:00 (granice da się zmienić)
- „Kiedy mam dentystę?” → szuka po tytule w najbliższych tygodniach
- „Dodaj spotkanie z Anną w piątek o 14 na godzinę w kawiarni Relaks”
- „Przesuń dentystę na 16” → najpierw lista, potem zmiana wybranego wydarzenia, długość zostaje ta sama
- „Dodaj termin płatności OC do kalendarza” → Jarvis bierze datę i kwotę z maila lub PDF-a i dodaje wydarzenie na cały dzień z przypomnieniem dzień wcześniej

### Okno zgody

Przed każdym dodaniem lub zmianą na PC pojawia się okno, np.:

```
Jarvis chce dodać wydarzenie do kalendarza
Tytuł: Spotkanie z Anną
Kiedy: piątek, 16 października 2026, 14:00–15:00
Gdzie: Kawiarnia Relaks
Kalendarz: twoj.adres@gmail.com
Uwaga, koliduje z: „Dentysta” piątek, 16 października 2026, 14:30–15:30
```

„Zezwól” dodaje wydarzenie, „Odmów” niczego nie zmienia. Każda zmiana pyta osobno, opcja „zawsze zezwalaj” nie przechodzi na kolejne wydarzenia. Wydarzenia dodane przez Jarvisa mają w Google ukryty znacznik `createdBy=jarvis`.

## Co Jarvis widzi

- Wydarzenia z kalendarzy, które masz **zaznaczone** w Google Calendar (np. własny, rodzinny, święta). Kalendarze ukryte pomija, chyba że nazwiesz je w prośbie („co mam w kalendarzu Praca?”).
- Godziny w Twoim czasie (`Europe/Warsaw`, ustawienie `jarvis.calendar.time-zone`), nawet jeśli serwer działa w UTC.
- Wydarzenia „wolne” (np. całodniowe przypomnienia) są na liście, ale nie blokują wolnych terminów. Odrzucone zaproszenia też ich nie blokują.

## Bezpieczeństwo

- Każdy widzi tylko swój kalendarz. Konto Google jest przypisane do konta Jarvisa w Core, a użytkownik pochodzi z sesji logowania, nie od modelu.
- Token jest zaszyfrowany w bazie Core (AES-256-GCM), tak jak przy Gmailu.
- Zgoda na zmianę pojawia się tylko na PC właściciela kalendarza, bo most do PC jest przypisany do konta. Bez PC zmiany są niemożliwe, a odczyt działa dalej.
- Tytuły i opisy wydarzeń z zaproszeń to dane, nie polecenia. Ślady diagnostyczne (`data/traces`) nie zawierają treści kalendarza.
- „Rozłącz kalendarz” usuwa połączenie i odwołuje zgodę w Google. Ręcznie można to zrobić na https://myaccount.google.com/permissions.

## Gdy coś nie działa

| Co widzisz | Co zrobić |
|---|---|
| Jarvis: „Google Calendar denied this request (403)” | Nie włączono Calendar API (krok 1) albo kalendarz jest tylko do odczytu |
| Jarvis: „permissions were not all granted” | Przy łączeniu nie zaznaczono obu pól. Połącz ponownie |
| Jarvis: „needs the Jarvis Windows app” | Do połączenia i do zatwierdzania zmian potrzebna jest aplikacja Jarvis na PC, zalogowana na to konto |
| Jarvis: „The user declined” | Kliknięto „Odmów”. Nic się nie zmieniło |
| Jarvis: „invalid_grant” | Zgoda wygasła lub została odwołana. Połącz kalendarz jeszcze raz |
