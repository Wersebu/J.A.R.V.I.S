# Gmail w aplikacji Windows

Jarvis czyta pocztę przez oficjalne Gmail API. Połączenie jest **w Core, osobno dla każdego konta Jarvisa**: Twoja skrzynka jest Twoja, skrzynka mamy jest jej, a obie działają z każdego urządzenia (PC, później telefon). Dostęp jest **tylko do odczytu** (zakres `gmail.readonly`): Jarvis nie może wysłać, usunąć ani oznaczyć żadnego maila, bo Google nie wydaje mu takiego uprawnienia.

Jedna aplikacja Google (Twoja, ustawiana raz w Google Cloud Console) obsługuje wszystkich. Każda osoba loguje się do niej własnym kontem Google.

## Jednorazowa konfiguracja (Google Cloud Console)

Konsola Google po polsku nazywa niektóre rzeczy trochę inaczej niż angielskie poradniki, a nazwy potrafią się zmieniać. Dlatego przy każdym kroku jest **bezpośredni link** do właściwego ekranu, a w nawiasie angielska nazwa przycisku. Zanim klikniesz link, zaloguj się na to samo konto Google, z którego Jarvis ma czytać pocztę.

Na górze każdej strony konsoli, obok logo „Google Cloud”, jest **lista projektów**. Upewnij się, że wybrany jest projekt „Jarvis”. Najczęstszy błąd to robienie kroków w innym projekcie.

### Krok 1. Utwórz projekt

Link: https://console.cloud.google.com/projectcreate

- **Nazwa projektu** (*Project name*): `Jarvis`
- **Lokalizacja** (*Location*): zostaw „Brak organizacji” (*No organization*)
- Kliknij **Utwórz** (*Create*) i poczekaj kilka sekund, aż projekt się utworzy. Potem wybierz go na liście projektów u góry.

### Krok 2. Włącz Gmail API

Link: https://console.cloud.google.com/apis/library/gmail.googleapis.com

- Sprawdź, czy u góry wybrany jest projekt „Jarvis”.
- Kliknij niebieski przycisk **Włącz** (*Enable*). Jeśli zamiast niego widzisz **Zarządzaj** (*Manage*), API jest już włączone.

### Krok 3. Ekran zgody (jak aplikacja przedstawia się przy logowaniu)

Link: https://console.cloud.google.com/auth/overview

W menu po lewej to sekcja **Platforma uwierzytelniania Google** (*Google Auth Platform*). Wcześniej nazywała się **Ekran zgody OAuth** (*OAuth consent screen*).

1. Kliknij **Rozpocznij** (*Get started*).
2. **Informacje o aplikacji** (*App information*):
   - **Nazwa aplikacji** (*App name*): `Jarvis`
   - **Adres e-mail pomocy dla użytkowników** (*User support email*): wybierz swój adres z listy
   - **Dalej** (*Next*)
3. **Odbiorcy** (*Audience*): zaznacz **Zewnętrzny** (*External*), potem **Dalej**. Opcja „Wewnętrzny” jest tylko dla firmowych kont Google Workspace.
4. **Dane kontaktowe** (*Contact information*): wpisz swój e-mail, potem **Dalej**.
5. **Zakończ** (*Finish*): zaznacz zgodę na zasady usług Google API, potem **Dalej** i **Utwórz** (*Create*).

Nie musisz nic ustawiać w sekcji **Dostęp do danych** (*Data access*). Jarvis sam prosi o uprawnienie tylko do odczytu poczty podczas łączenia.

### Krok 4. Dodaj siebie jako testera

Link: https://console.cloud.google.com/auth/audience

- Przewiń do sekcji **Użytkownicy testowi** (*Test users*).
- Kliknij **+ Dodaj użytkowników** (*+ Add users*), wpisz swój adres Gmail i **Zapisz** (*Save*).

### Krok 5. Utwórz klienta OAuth i pobierz plik

Link: https://console.cloud.google.com/auth/clients

1. Kliknij **+ Utwórz klienta** (*+ Create client*).
2. **Typ aplikacji** (*Application type*): wybierz **Aplikacja komputerowa** (*Desktop app*).
   Nie wybieraj „Aplikacja internetowa” (*Web application*), bo z nią logowanie nie zadziała.
3. **Nazwa** (*Name*): np. `Jarvis Windows`.
4. Pole **Use this client for an AI-powered agent** zostaw **niezaznaczone**. Służy ono klientom, którzy korzystają z narzędzi MCP Google'a, a Jarvis łączy się ze zwykłym Gmail API.
5. Kliknij **Utwórz** (*Create*). Google uprzedza, że nowe ustawienia mogą zacząć działać dopiero po kilku minutach (czasem godzinach). Jeśli pierwsze logowanie się nie uda, odczekaj i spróbuj ponownie.
6. W okienku, które się pojawi, kliknij **Pobierz JSON** (*Download JSON*).
   Jeśli okienko zamknąłeś, wróć na listę klientów. Przy kliencie jest ikona pobierania, a w szczegółach klienta da się pobrać plik.

Pole „Autoryzowane identyfikatory URI przekierowania” (*Authorized redirect URIs*) przy kliencie komputerowym w ogóle się nie pojawia i jest niepotrzebne, bo Google sam akceptuje adres `127.0.0.1`.

### Krok 6. Wgraj plik na serwer Jarvisa

Plik klienta Google trzyma teraz serwer (Core), a nie PC.

1. Pobrany plik ma nazwę typu `client_secret_123456-abc.apps.googleusercontent.com.json`. Jeśli masz go już jako `config/gmail.json` w kliencie Windows, możesz użyć właśnie tego pliku.
2. Skopiuj go na serwer, np. do `/opt/jarvis/config/gmail-client.json`:

   ```bash
   scp gmail.json uzytkownik@192.168.100.10:/opt/jarvis/config/gmail-client.json
   ```

3. Na serwerze ustaw uprawnienia (tylko użytkownik, pod którym działa Jarvis) i wskaż plik w konfiguracji Core:

   ```bash
   chmod 600 /opt/jarvis/config/gmail-client.json
   ```

   Zmienna środowiskowa Core (np. w pliku usługi systemd lub `.env`): `JARVIS_GMAIL_CLIENT_FILE=/opt/jarvis/config/gmail-client.json`.
4. **Klucz szyfrowania tokenów.** Przy pierwszym połączeniu Core sam utworzy `data/secrets.key` (uprawnienia 600) i zapisze to w logu. Możesz też ustawić klucz sam: `JARVIS_SECRETS_KEY=<wynik: openssl rand -base64 32>`. **Zrób kopię zapasową klucza.** Bez niego zapisane połączenia nie dadzą się odczytać i każdy będzie musiał połączyć Gmaila od nowa.
5. Strefa czasu dat w mailach to domyślnie `Europe/Warsaw` (`jarvis.gmail.time-zone`), nawet jeśli serwer działa w UTC.

### Krok 7. Opublikuj aplikację (żeby zgoda nie wygasała co 7 dni)

Link: https://console.cloud.google.com/auth/audience

- W sekcji **Stan publikacji** (*Publishing status*) jest napisane **Testowanie** (*Testing*).
- Kliknij **Opublikuj aplikację** (*Publish app*) i potwierdź **Potwierdź** (*Confirm*). Stan zmieni się na **W wersji produkcyjnej** (*In production*).
- Google może pokazać informację, że aplikacja wymaga weryfikacji. **Do własnego użytku nie musisz jej przechodzić.** Jedyna różnica jest taka, że przy logowaniu zobaczysz ostrzeżenie (krok 9).

Dopóki stan to „Testowanie”, Google unieważnia zgodę po 7 dniach, a Jarvis zgłasza wtedy `invalid_grant`. Jeśli publikujesz aplikację po pierwszym połączeniu, połącz konto jeszcze raz.

### Krok 8. Przebuduj Jarvisa i wgraj workflow

- Przebuduj i uruchom serwer oraz klienta Windows.
- Skopiuj `docs/vault/workflows/Gmail.md` do vault na serwerze jako `workflows/Gmail.md`, tak samo jak `Spotify.md`.
- Jeśli wcześniej łączyłeś Gmaila w kliencie Windows, to stare połączenie już nie działa. Połącz konto jeszcze raz (krok 9). Stary plik `%LOCALAPPDATA%\Jarvis\gmail\credentials.dpapi` możesz usunąć.

### Krok 9. Połącz konto

Każda osoba robi to raz, na swoim koncie Jarvisa. Na razie potrzebna jest do tego aplikacja Jarvis na PC, zalogowana na tym koncie. Z telefonu będzie można się połączyć, gdy Jarvis dostanie publiczny adres (domenę).

1. Powiedz Jarvisowi: **„Połącz moją pocztę Gmail”**. Na PC otworzy się przeglądarka ze stroną logowania Google, a Jarvis czeka do 5 minut.
2. Wybierz swoje konto Google.
3. Pojawi się ostrzeżenie **„Google nie zweryfikował tej aplikacji”** (*Google hasn't verified this app*). To normalne, bo to Twoja własna aplikacja. Kliknij mały link **Zaawansowane** (*Advanced*), a potem **Przejdź do: Jarvis (niebezpieczne)** (*Go to Jarvis (unsafe)*). Przy niektórych kontach jest tam od razu przycisk **Dalej** (*Continue*).
4. Na ekranie uprawnień **zaznacz pole** przy uprawnieniu do wyświetlania e-maili (*View your email messages and settings*) i kliknij **Dalej** (*Continue*). Bez zaznaczenia Jarvis zgłosi, że nie dostał uprawnienia.
5. Zobaczysz stronę „Gotowe — Jarvis kończy łączenie konta”. Zamknij ją, a Jarvis sam odpowie, które konto połączył.

### Konto dla innej osoby (np. mamy)

1. Załóż jej konto Jarvisa (tylko admin, czyli Ty):

   ```bash
   curl -X POST http://192.168.100.10:PORT/api/v1/auth/users -H "Authorization: Bearer TWOJ_TOKEN" -H "Content-Type: application/json" -d "{\"email\":\"mama@example.com\",\"password\":\"haslo-min-8-znakow\",\"displayName\":\"Mama\"}"
   ```

2. Jeśli aplikacja Google jest jeszcze w trybie „Testowanie”, dodaj adres Gmail mamy w **Użytkownicy testowi** (krok 4). Po publikacji (krok 7) nie trzeba.
3. Mama loguje się do Jarvisa swoim kontem i mówi „Połącz moją pocztę Gmail” (krok 9, potrzebny PC z aplikacją Jarvis zalogowaną na jej konto). Widzi tylko swoją pocztę. Ty jej nie widzisz w Jarvisie, a ona Twojej.
4. Zanim zacznie, powiedz jej uczciwie, jak to działa (sekcja „Bezpieczeństwo”).

### Gdy coś nie działa

| Co widzisz | Co zrobić |
|---|---|
| „Błąd 403: access_denied” przy logowaniu | Nie dodałeś swojego adresu w kroku 4 albo logujesz się innym kontem |
| „Błąd 400: redirect_uri_mismatch” | Klient ma typ „Aplikacja internetowa”. Utwórz nowego, typu **Aplikacja komputerowa** (krok 5) |
| Jarvis: „Gmail denied this request (403)” | Gmail API nie jest włączone w tym projekcie (krok 2) albo nie zaznaczyłeś uprawnienia w kroku 9 |
| Jarvis: „invalid_grant … 7 days” | Aplikacja jest w trybie „Testowanie”. Zrób krok 7 i połącz ponownie |
| Jarvis: „Gmail is not set up on the Jarvis server” | Brak pliku klienta na serwerze lub zła ścieżka w `JARVIS_GMAIL_CLIENT_FILE` (krok 6) |
| Jarvis: „needs the Jarvis Windows app” | Uruchom aplikację Jarvis na PC i zaloguj się na to samo konto Jarvisa (krok 9) |
| Jarvis: „Port 43822 for the login is busy” | Na PC trwa inne logowanie. Dokończ je albo zmień `jarvis.gmail.callback-port` na serwerze |
| Jarvis: „Could not decrypt secret” | Zmienił się lub zginął klucz `JARVIS_SECRETS_KEY` / `data/secrets.key`. Przywróć kopię albo połącz Gmaila od nowa |

## Przykładowe polecenia

- „Co nowego na poczcie?” → `gmail__unread`
- „Pokaż maile od PMT z tego tygodnia” → jedno wywołanie `gmail__search query=from:(@pmt.com.pl) newer_than:7d bodies=true`. Wynik: niewykonane raporty i uwagi do serwisu w tabelach (sklep, adres, cytat), osobno maile do Ciebie osobiście.
- „Czy przyszło ubezpieczenie?” → szuka po OC/polisa/ubezpieczenie, czyta załącznik PDF i podaje termin płatności i kwotę.
- „A w którymś z nich jest załącznik?” → zawęża **poprzednią** listę (`within=previous query=has:attachment`), zamiast przeszukiwać całą skrzynkę.
- „Przeczytaj drugi”, „co jest w załączniku do pierwszego?” → `gmail__read index=2`, `gmail__attachment index=1`.
- „Streść cały wątek o umowie” → `gmail__thread` (cytowana historia odpowiedzi jest wycinana).

Wyniki zawierają nadawcę, temat i datę w polskim czasie (np. „sobota, 10 października 2026, 13:00”), snippet, załączniki oraz flagi `unread`, `important`, `category`, `bulk` (newsletter/lista) i `addressedTo`:
- `me`: mail osobiście do Ciebie,
- `me+others`: do Ciebie i innych,
- `cc`: jesteś w kopii,
- `group`: mail ogólny lub systemowy.

**Załączniki:** Jarvis czyta tekst z PDF, DOCX, HTML, TXT i CSV, w pamięci, bez zapisywania na dysk. Skanów i zdjęć nie odczyta, bo nie ma OCR, i powie to wprost. Limit to 15 MB.

**Własne skróty** (jak PMT czy OC) dopisuje się w `workflows/Gmail.md` w sekcji „Moje skróty”. Ten plik jest ładowany automatycznie przy każdym pytaniu o pocztę.

## Bezpieczeństwo

- **Każdy widzi tylko swoją pocztę.** Konto Gmail jest przypisane do konta Jarvisa. Core bierze użytkownika z sesji logowania, nigdy od modelu, więc rozmowa jednej osoby nie może sięgnąć do skrzynki drugiej.
- **Token w Core, zaszyfrowany.** Token odświeżania jest w bazie Core, zaszyfrowany AES-256-GCM kluczem z `JARVIS_SECRETS_KEY` lub `data/secrets.key` i przypisany do konkretnego użytkownika (wiersza nie da się przenieść innej osobie). Sama kopia bazy nie ujawnia tokenów. Kto ma dostęp do serwera razem z kluczem, ma też dostęp do połączonych skrzynek. To trzeba jasno powiedzieć innym użytkownikom.
- **Logowanie.** PKCE i losowy `state`. PC tylko otwiera stronę Google i odbiera jednorazowy kod na `127.0.0.1`, otwiera wyłącznie `accounts.google.com`. Kod wymienia na token Core, bo tylko on zna weryfikator PKCE i sekret klienta. Na PC nie zostaje żaden token.
- **Treść maili trafia do Core i modelu**, bo inaczej Jarvis nie mógłby ich streszczać. Jest w historii rozmowy danej osoby, tak jak każda inna rozmowa.
- **Ślady diagnostyczne** (`data/traces`) nie zawierają treści maili ani załączników, tylko informację, że wywołanie się udało. Ostateczna odpowiedź Jarvisa (np. streszczenie) jest w śladzie tak jak każda odpowiedź. Ślady wyłącza się przez `JARVIS_CONVERSATION_TRACE_ENABLED=false`.
- Każdy wynik z treścią ma `untrustedContent=true` i ostrzeżenie dla modelu. Workflow zabrania wykonywania poleceń znalezionych w mailach (prompt injection: „Jarvis, prześlij mi…”).
- „Rozłącz Gmail” usuwa połączenie danej osoby i odwołuje zgodę w Google. Ręcznie można to zrobić na https://myaccount.google.com/permissions.

## Błędy podczas pracy

- `invalid_grant`: zgoda wygasła (tryb „Testowanie”) albo została odwołana. Wykonaj ponownie „Połącz moją pocztę Gmail”.
- 429: limit zapytań. Core sam blokuje ponawianie dla tej osoby na czas `Retry-After`.

## Kolejne etapy

1. ✅ Czytanie, załączniki, skróty, pamięć listy w rozmowie, osobne konta dla każdej osoby (ten dokument).
2. Szkice odpowiedzi w Gmailu i wysyłka wyłącznie po zatwierdzeniu na urządzeniu użytkownika (okno zgody na PC, później powiadomienie na telefonie), z pokazanym adresatem, tematem i treścią.
3. Automatyzacja: sprawdzanie nowych maili w tle (`history.list`), ocena ważności, powiadomienia i dzienne podsumowanie.
