# Spotify: odtwarzanie z dowodami zamiast deklaracji modelu

Zakres obejmuje trzy zgłoszone przebiegi gpt-oss:20b: URI z pamięci i fallback do przeglądarki,
polubione zastąpione prezentacją fikcyjnej playlisty oraz odziedziczony nieprawdziwy link,
connect po błędzie URI i nieobsługiwane WAIT. Nie analizowano logów Ubuntu tych przebiegów;
nie potwierdzamy konkretnego wysłanego URI ani przyczyny decyzji modelu o logowaniu.
Regresje odtwarzają opisane sekwencje kontrolowanym modelem.

## Ustalenia w kodzie

- NativeToolLoopService zapamiętywał fingerprint identycznego devices przez cały request.
  OPEN nie należało do changesFiles, więc po uruchomieniu Spotify identyczny odczyt mógł być
  DUPLICATE_TOOL_CALL. To potwierdza mechanizm, nie fakt wykonania takiej próby w logach.
- Windows nie blokuje kolejnych devices. Oddziela accepted od observedPlayback, ale wcześniejszy
  Core nie wymagał zgodności URI/urządzenia/isPlaying przed uznaniem celu za zakończony.
- ToolCallingStage przekazuje oryginalną wiadomość obok podcelu modelu. Problemem było uznawanie
  dowolnego wyniku (np. liked) za wykonanie celu, a nie utrata oryginalnego tekstu w transporcie.
- NativeToolSchemaMapper pomijał walidację nieznanej operacji; późniejsza kontrola sprawdzała
  wyłącznie istnienie narzędzia. pc__wait mogło dotrzeć do wykonawcy, który go nie obsługuje.
  Literalne pc_wait jako nazwa funkcji natywnej było już odrzucane przez normalizację nazwy.
- Prawidłowe pola play to action=play, target (URI/URL), deviceId lub deviceName. Nowa procedura
  wybiera kanoniczne URI oraz deviceId z dowodów. Pole uri zamiast target jest błędem schematu.
- Walidacja URI w Windows sprawdza format, nie pochodzenie. Syntaktycznie poprawnego URI z pamięci
  nie dało się odróżnić od wyniku wyszukiwania. Robi to teraz Core na podstawie bieżących wyników.

## Zmiany Core

SpotifyPlaybackPolicy rozpoznaje oryginalną prośbę o odtwarzanie (PL/EN); podcel i historia mogą
pomóc ustalić, że chodzi o Spotify, ale nie autoryzują URI ani innego celu. requiredOutcome
zachowuje oryginalną prośbę, także przy podcelu „Fetch and present a playlist…”.

Przed wykonaniem procedury wymagany jest pełny odczyt workflows/Spotify.md przez READ_WORKFLOW
lub READ_DOCUMENT. Uwzględniono części dokumentu z tą samą wersją. Brak/niepełny workflow nie
jest zastępowany procedurą z pamięci. Core dodaje tylko krótki routing do pętli, nie cały jarvis.md.
Nie zmieniono plików workflow ani tożsamości na Ubuntu.

Następnie: status, devices, ewentualne OPEN(target=spotify), devices ponownie, wybór zasobu,
play, weryfikacja. Dla polubionych źródłem musi być liked; dla pozostałych próśb search/library.
Liked może być odczytane przed listą urządzeń, ale play wymaga obu dowodów. URI musi być dokładnie
zwrócone w aktualnym requestcie — nie z historii odpowiedzi asystenta, modelowej pamięci czy
z wymyślonego linku. Identyfikator urządzenia musi odpowiadać jedynemu lokalnemu komputerowi.

Odczyty status/devices/current dostają nowe fingerprinty dopiero po rzeczywistym wykonaniu
OPEN aplikacji lub próbie play. Każdy ma limit czterech wykonanych odczytów w requestcie.
Przed zmianą stanu identyczny odczyt nadal jest duplikatem. Pozwala to również obserwować
asynchroniczną gotowość po OPEN i playback po play. Odrzucona, nieprzeprowadzona mutacja nie
odblokowuje odczytów. Drugi play nie jest automatycznie wysyłany nawet po niejednoznacznym błędzie;
wtedy należy sprawdzić current albo zgłosić częściowy wynik. Istniejące limity tur nie wzrosły.
Sześć naruszeń procedury kończy request jako nieukończony zamiast zapętlać próby obejścia.

Nie ma fallbacku przez browser, OPEN URL, MEDIA ani shell. Connect/disconnect i create_playlist
nie są zastępstwem odtwarzania; ta procedura ich nie dopuszcza. Błędny target prowadzi do
wskazania właściwego źródła URI. Nieznane operacje, w tym pc__wait, są teraz odrzucane przed
wykonaniem niezależnie od procedury. Test Store Audit uzupełniono o brakującą deklarację istniejącej
operacji SET_PREFERENCES; nie zmieniono jego oczekiwań.

Ukończenie wymaga accepted oraz obserwacji isPlaying=true na wybranym deviceId, z wybranym URI
utworu/odcinka lub contextUri dla istniejącego albumu/artysty/playlisty. Obserwacja pochodzi z
observedPlayback albo późniejszego current; późniejszy nieudany/negatywny odczyt jest nadrzędny.
Końcowy tekst powstaje z dowodów: zaobserwowana nazwa i wykonawcy, źródłowa nazwa jako fallback,
ustalone urządzenie. Nie zawiera wymyślonych linków ani twierdzeń o utworzeniu playlisty.
Brak potwierdzenia daje completed=false, goalSatisfied=false i jawny częściowy wynik.

## Zmiany Windows i zgodność wersji

Devices oznacza local=true wyłącznie dla jednoznacznego, nieograniczonego urządzenia Computer
zgodnego z deviceName w konfiguracji lub COMPUTERNAME. Telefony, Web Player i niejednoznaczne
nazwy nie są lokalnym celem. Podanie jawnego ID nie pozwala już ominąć lokalnej identyfikacji
i wskazać innego komputera. Nie wpisano w kod nazwy komputera użytkownika ani konkretnego utworu.
Playback zwraca także contextUri, potrzebne do weryfikacji istniejących kontekstów odtwarzania.

Nowy Core potrzebuje nowego Windows dla tej procedury. Starszy klient nie zwraca local=true;
Core wtedy zgłosi brak identyfikacji zamiast wybierać komputer heurystycznie. Gdy aplikacja ma
inną nazwę w Spotify Connect niż COMPUTERNAME, użytkownik musi ustawić faktyczne deviceName
w swojej konfiguracji Windows. Nie uruchamiamy automatycznie logowania ani nie zmieniamy configu.

## Walidacja

- Pełne `mvn -o -pl jarvis-core -am verify`: BUILD SUCCESS; 818 testów, zero failures/errors/skipped.
- Nowe regresje pętli: 15 scenariuszy playback, w tym zawężony podcel, fikcyjny link z historii,
  liked bez play, nieprawdziwy target, connect/WAIT, odczyty po OPEN, ograniczenia ponowień,
  fallback przeglądarkowy, niezgodny URI/device i brak isPlaying. Dotychczasowe 17 testów
  status/devices nadal przechodzi.
- Windows PcSpotifyTest + SpotifyTokenStoreTest: 18 testów, zero błędów.
- Pełny Windows: 148 testów, 5 failures, 1 error, 4 skipped. Porażki to cztery testy powłoki
  PcAgentToolsTest, jeden PcApprovalsTest i WindowsPcExecutorTest.symlinkInsideRootCannotEscape.
  Te same 5 failures + 1 error odtworzono w osobnym archiwum niezmienionego HEAD a8f8246:
  testy powłoki używają składni uniksowej przy domyślnym cmd.exe; dowiązanie wymaga uprawnień
  niedostępnych w środowisku. Nie zmieniano ani nie pomijano tych oczekiwań.
- Pakiet Windows: `mvn -o -DskipTests package` BUILD SUCCESS po osobnym wykonaniu testów.
- Testy korzystają z atrap Spotify i lokalnego HTTP; nie logowano się do prawdziwego Spotify
  ani nie odtwarzano muzyki. Nie przeprowadzono nowego testu produkcyjnego na gpt-oss:20b.

Zmian nie wdrożono i nie wysłano przez push. Repozytoria Core i Windows są osobne;
zmiany vault/UI obecne wcześniej w Windows pozostają poza tą poprawką.
