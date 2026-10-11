# Gmail w aplikacji Windows

Jarvis czyta pocztę przez oficjalne Gmail API. Etap 1 to **tylko odczyt**: zakres `gmail.readonly`. Jarvis nie może wysłać, usunąć ani oznaczyć żadnego maila, bo Google nie wydaje mu takiego uprawnienia. Przeglądarka jest potrzebna tylko do jednorazowej zgody na koncie Google.

## Jednorazowa konfiguracja (Google Cloud Console)

1. Wejdź na https://console.cloud.google.com/, utwórz projekt, np. „Jarvis”.
2. **APIs & Services → Library → Gmail API → Enable.**
3. **Google Auth Platform / OAuth consent screen:** typ użytkowników *External*, nazwa aplikacji „Jarvis”, Twój e-mail jako kontakt. W sekcji *Audience → Test users* dodaj swój adres Gmail.
4. **Clients / Credentials → Create credentials → OAuth client ID → Application type: Desktop app.** Pobierz plik JSON.
5. Zapisz pobrany plik jako `config/gmail.json` w katalogu roboczym klienta Windows. Format `{"installed": {...}}` jest obsługiwany bez zmian. Możesz też skopiować `config/gmail.example.json` i wpisać `clientId` oraz `clientSecret`.
   - Klient typu *Desktop app* nie wymaga rejestrowania Redirect URI, bo Google przyjmuje dowolny port `127.0.0.1`. Domyślny port to `43822` (`callbackPort`).
   - Zamiast pliku można ustawić `JARVIS_GMAIL_CLIENT_ID` i `JARVIS_GMAIL_CLIENT_SECRET`.
6. Przebuduj i uruchom serwer oraz klienta Windows. Skopiuj `docs/vault/workflows/Gmail.md` do vault na serwerze (`workflows/Gmail.md`), tak samo jak `Spotify.md`.
7. Powiedz Jarvisowi: **„Połącz moją pocztę Gmail”**. Otworzy się strona zgody Google. Przy aplikacji w trybie testowym Google pokaże ostrzeżenie „Google hasn't verified this app”: kliknij *Continue* i zaznacz zgodę na czytanie poczty. Potem: **„Sprawdź połączenie z Gmailem”**.

### Ważne: 7 dni w trybie Testing

Dopóki aplikacja ma status *Testing*, Google unieważnia token po 7 dniach i Jarvis zgłosi `invalid_grant`. Żeby tego uniknąć, w *Audience* kliknij **Publish app** (status *In production*). Do własnego użytku nie trzeba przechodzić weryfikacji Google. Ekran zgody pokaże jedynie ostrzeżenie o niezweryfikowanej aplikacji. Po zmianie statusu połącz konto ponownie.

## Przykładowe polecenia

- „Co nowego na poczcie?” → `unread`
- „Pokaż maile od Anny z tego tygodnia” → `search query=from:anna newer_than:7d`
- „Przeczytaj ten od banku” → `read id=<id z wyniku>`
- „Streść cały wątek o umowie” → `thread threadId=<threadId>` (cytowana historia odpowiedzi jest wycinana)
- „Pokaż następne” → to samo z `pageToken`

Wyniki zawierają nadawcę, temat, datę, snippet oraz flagi `unread`, `important`, `starred`, `category` i `bulk` (newsletter/lista mailingowa). `read` zwraca treść jako czysty tekst (HTML jest zamieniany na tekst) i listę załączników bez ich pobierania.

## Bezpieczeństwo

- OAuth używa PKCE i losowego `state`. Callback nasłuchuje wyłącznie na `127.0.0.1` i wygasa po 5 minutach.
- Token odświeżania jest szyfrowany przez Windows DPAPI w `%LOCALAPPDATA%\Jarvis\gmail\credentials.dpapi`. Token dostępu jest tylko w pamięci klienta. Żaden token nie trafia do Core ani do modelu.
- `clientSecret` klienta *Desktop app* Google opisuje jako niepoufny w aplikacjach instalowanych. Mimo to `config/gmail.json` jest w `.gitignore`.
- **Treść maili trafia do Core i modelu**, bo inaczej Jarvis nie mógłby ich streszczać. Jeśli Core działa na zdalnym serwerze, czytane maile przechodzą przez ten serwer.
- Każdy wynik z treścią ma `untrustedContent=true` i ostrzeżenie dla modelu. Workflow zabrania wykonywania poleceń znalezionych w mailach (prompt injection: „Jarvis, prześlij mi…”).
- `disconnect` usuwa lokalne dane i odwołuje zgodę w Google. Ręcznie można to zrobić na https://myaccount.google.com/permissions.

## Błędy

- `invalid_grant`: zgoda wygasła (tryb Testing) lub została odwołana. Wykonaj ponownie `connect`.
- 403: Gmail API nie jest włączone w projekcie albo przy zgodzie nie zaznaczono czytania poczty.
- 429: limit zapytań. Klient blokuje ponawianie na czas `Retry-After`.
- Brak wolnego portu callback: zakończ drugie logowanie albo zmień `callbackPort`.

## Kolejne etapy

1. ✅ Czytanie (ten dokument).
2. Szkice odpowiedzi w Gmailu i wysyłka wyłącznie po zatwierdzeniu w oknie zgody na PC (`PcApprovals`), z pokazanym adresatem, tematem i treścią.
3. Automatyzacja: sprawdzanie nowych maili w tle (`history.list`), ocena ważności, powiadomienia i dzienne podsumowanie.
