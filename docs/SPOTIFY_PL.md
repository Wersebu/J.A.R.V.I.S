# Spotify w aplikacji Windows

Jarvis odczytuje bibliotekę i wyszukuje muzykę przez oficjalne Spotify Web API. Odtwarzaniem steruje przez Spotify Connect na wybranym komputerze. Przeglądarka jest potrzebna tylko do jednorazowej zgody na połączenie konta — nie odtwarza muzyki.

## Jednorazowa konfiguracja

1. Wejdź na https://developer.spotify.com/dashboard i zaloguj się kontem Spotify Premium. Utwórz aplikację, np. „Jarvis Windows”, z dostępem do Web API.
2. W ustawieniach aplikacji dodaj dokładny Redirect URI: **`http://127.0.0.1:43821/callback`**. Nie używaj `localhost`.
3. Skopiuj **Client ID**. Client Secret nie jest potrzebny i nie należy go wpisywać do czatu ani konfiguracji Jarvisa. Jeśli używasz innego konta niż właściciel aplikacji, dodaj je do listy użytkowników aplikacji w panelu Spotify.
4. W katalogu roboczym klienta Windows skopiuj `config/spotify.example.json` do `config/spotify.json` i uzupełnij:

   ```json
   {
     "clientId": "TUTAJ_CLIENT_ID_Z_PANELU_SPOTIFY",
     "callbackPort": 43821,
     "deviceName": ""
   }
   ```

   Można też ustawić zmienną środowiskową `JARVIS_SPOTIFY_CLIENT_ID` przed uruchomieniem klienta. Puste `deviceName` oznacza nazwę komputera Windows (`COMPUTERNAME`). Jeśli Spotify pokazuje inną nazwę, wpisz nazwę z wyniku `devices`.
5. Przebuduj i uruchom aktualny serwer oraz klienta Windows. Otwórz aplikację Spotify na komputerze i zaloguj się tym samym kontem.
6. Powiedz Jarvisowi: **„Połącz moje konto Spotify”**. Jarvis użyje `pc__spotify action=connect` i otworzy stronę zgody. Zatwierdź dostęp w ciągu 5 minut, potem powiedz **„Sprawdź połączenie Spotify i urządzenia”**. Gdy nie uda się otworzyć przeglądarki automatycznie, otwórz zwrócony link autoryzacyjny.

Client ID można wpisać przy działającym kliencie — konfiguracja jest odczytywana ponownie. Przy zmianie portu zmień również Redirect URI w panelu Spotify. `status` pokazuje lokalnie zapisane połączenie; dopiero wywołanie API weryfikuje ważność dostępu.

## Przykładowe polecenia

- „Pokaż moje polubione piosenki” → `liked`, dalsze strony przez `nextOffset`.
- „Pokaż zapisane albumy” → `library type=album`; „moje playlisty” → `playlists`.
- „Znajdź Daft Punk i zagraj wybrany utwór na tym komputerze” → `search`, potem `play target=<URI z wyniku>`.
- „Co teraz gra?”, „następny”, „pauza”, „głośność 30%” → `current`, `next`, `pause`, `volume value=30`.
- „Dodaj ten utwór do kolejki/polubionych” → `enqueue` / `save` z URI utworu.
- „Utwórz prywatną playlistę…” → `create_playlist`; „dodaj do niej ten utwór” → `add_to_playlist`.

`play` bez `target` wznawia odtwarzanie, a z URI albumu lub playlisty uruchamia dany kontekst. Polecenia sterujące zawsze przekazują konkretny `device_id`. Jeśli nazwa lokalnego komputera nie pasuje, Jarvis zgłosi potrzebę wyboru urządzenia zamiast automatycznie przełączać muzykę na telefon lub inny komputer. Po komendzie wynik zawiera `accepted` oraz osobno `observedPlayback`; Spotify może zaktualizować stan z opóźnieniem.

## Dane połączenia i błędy

OAuth używa PKCE i losowego `state`. Callback nasłuchuje wyłącznie na `127.0.0.1` i wygasa po 5 minutach. Token odświeżania jest szyfrowany przez Windows DPAPI dla bieżącego użytkownika i zapisywany w `%LOCALAPPDATA%\Jarvis\spotify\credentials.dpapi`. Token dostępu pozostaje w pamięci klienta. Żaden token ani hasło nie trafia do wyników narzędzia, serwera Jarvisa ani modelu.

`disconnect` usuwa lokalne dane połączenia. Dostęp aplikacji można też odwołać na stronie konta Spotify w sekcji Apps.

- 401 / cofnięta zgoda: ponownie `connect`. Klient odświeża wygasły token automatycznie i ponawia żądanie po 401 najwyżej raz.
- 403: sprawdź Premium, listę uprawnionych użytkowników i zakres zgody. W nowym Development Mode odczyt zawartości playlist jest ograniczony do własnych/współtworzonych; to ograniczenie Spotify.
- 404 / brak urządzenia: uruchom Spotify na Windowsie, sprawdź konto i ponownie wywołaj `devices`.
- 429: poczekaj czas `Retry-After`; klient blokuje szybkie ponawianie żądań.
- Brak wolnego portu callback: zakończ drugie logowanie lub skonfiguruj inny port i odpowiadający mu Redirect URI.

## Dokumentacja Spotify

- [OAuth PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)
- [Redirect URI](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri)
- [Sterowanie odtwarzaniem i wymaganie Premium](https://developer.spotify.com/documentation/web-api/reference/start-a-users-playback)
- [Zmiany Development Mode w 2026 roku](https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide): nowe endpointy biblioteki i playlist, limit wyszukiwania 10 na stronę, właściciel aplikacji musi mieć Premium.

Testy lokalne używają atrap Spotify HTTP/OAuth oraz sprawdzają DPAPI. Test na prawdziwym koncie wymaga własnego Client ID i zgody użytkownika.
