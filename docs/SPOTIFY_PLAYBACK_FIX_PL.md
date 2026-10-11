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


## Odnajdywanie procedury i „polubione” (2026-10-11)

Przebieg przekazany przez użytkownika pokazuje wykonane odczyty zgadywanych
ścieżek, listingi, a potem tylko tekst zapowiadający wyszukiwanie. Nie zawiera
zdarzenia odbioru, walidacji ani wykonania search. Jest to przesłanka braku
native tool call, nie dowód blokady wyszukiwania przez Core. Bez surowego śladu
odpowiedzi modelu nie można rozstrzygnąć tego definitywnie.

W bieżącym kodzie READ_WORKFLOW i FIND_WORKFLOW wystawia ten sam vaultDefinition;
mapper dla Spotify nie usuwa FIND_WORKFLOW z katalogu. Polityka pozwala wykonać
FIND_WORKFLOW przed odczytem procedury. Dodano log SPOTIFY_WORKFLOW_DISCOVERY
z requestId i informacją o dostępności obu schematów. W połączeniu ze zdarzeniami
NATIVE_TOOL_CALL i krokami SPOTIFY_POLICY_BLOCKED pozwala odróżnić brak schematu,
brak wywołania modelu i odrzucenie wywołania przez politykę.

Polityka przyjmuje dokładną ścieżkę względną z wyniku FIND_WORKFLOW (także obiekt
WorkflowCandidate zwracany przez rzeczywisty KnowledgeTool), bez dopisywania
katalogów. Domyślny routing workflows/Spotify.md pozostaje poprawną ścieżką
względem skonfigurowanego vault. Błędny odczyt unieważnia ścieżkę do czasu
ponownego wyszukania. Zgadywanie następnych nazw i listingi zamiast odnalezienia
procedury są blokowane. Pusty wynik discovery nie uprawnia do użycia starej
ścieżki. Żaden katalog Ubuntu nie został zakodowany w poprawce.

Pełny odczyt jest wiązany ze ścieżką, wersją i kompletem części tego samego
workflow. Wymagania źródła URI, lokalnego urządzenia oraz obserwowanego playback
pozostają aktywne. Sam tekst „wyszukam” nadal nie stanowi dowodu wykonania.

Przy delegacji odtwarzania „z polubionych” zachowywany jest oryginalny cel i
jednoznaczne action=liked; błędny cel favorite playlists go nie zastępuje.
Regresje obejmują discovery → odczyt → status → liked → devices → play →
observedPlayback, odczyt wieloczęściowy, brak kandydatów, błędne prefiksy,
Document not found → discovery oraz zapowiedź search bez tool call.


## „Odpal coś” i lokalny komputer na słabszych modelach (2026-10-11)

Przebieg „siemanko odpal mi coś na spotify”: FIND_WORKFLOW → READ_WORKFLOW → status → devices
(lokalny PC znaleziony), po czym model zakończył tekstem bez search/play. Spotify.md każe przy
niejednoznaczności „zapytać krótko” i nie wymyślać gustu, a Core zamieniał taką odpowiedź na
„Zadanie nieukończone”. Wskazówka bramki ukończenia była ogólna (cała procedura), więc słaby model
nie wiedział, jakie konkretne wywołanie wykonać.

- Przy ogólnej prośbie źródłem może być action=liked (rzeczywiste dane konta użytkownika, nie
  wymyślony gust). Prośba „z polubionych” nadal wymaga wyłącznie liked.
- nextStep podaje jedno konkretne następne wywołanie (status/devices/open/search lub liked/play
  z gotowym URI i deviceId/current). Trafia do bramki ukończenia i do każdej blokady polityki.
- search nie wymaga już listy urządzeń; urządzenie jest potrzebne tylko dla play.
- prepare: dla play Core sam wpisuje deviceId lokalnego PC (z ostatniego devices), zastępując
  brak, deviceName lub zmyślone ID. Link open.spotify.com albo dokładna nazwa wyniku z bieżącego
  requestu zamienia się na jego URI. Cel spoza wyników nadal jest blokowany.
- Wcześniej słabe modele często dostawały blokadę „local=true Computer” za search przed devices
  albo play bez/ze złym deviceId; po kilku takich blokadach limit sześciu naruszeń kończył request.

Windows nadal oznacza local=true tylko gdy nazwa urządzenia w Spotify Connect jest równa
deviceName z config/spotify.json lub COMPUTERNAME. Gdy się różnią, trzeba ustawić deviceName.

Drugi przebieg (12:37) działał jeszcze na starym Core: komunikat „exact URI from search/library”
odrzucił URI z liked, czego nowa polityka już nie robi. Dodatkowo:
- pc__read preferences/Music.md dostawał mylący komunikat o MEDIA/browser; teraz Core wskazuje
  vault i każe przejść do liked, gdy notatki nie ma. knowledge__read_document innych notatek jest
  dozwolony po odczycie procedury.
- Windows: search i liked/library tracks pytają z market=from_token, więc Spotify zwraca
  is_playable; Core nie dopuszcza utworów is_playable=false ani lokalnych plików jako celu play.
- Windows: po play observedPlayback jest odczytywany do 6 razy co 500 ms, aż odtwarzacz pokaże
  isPlaying=true z wybranym URI na lokalnym PC. Pojedynczy natychmiastowy odczyt pokazywał stary,
  zatrzymany utwór („FART PHONK”, isPlaying=false).

Trzeci przebieg (12:55) ujawnił główną przyczynę „błędu lokalnego komputera” na słabych modelach:
model przepisywał identyfikatory z błędami (URI `…WUoB7…` → `…Wu0B7…`, deviceId bez ostatniego
znaku). Core wpisuje teraz deviceId lokalnego PC sam, a URI różniące się o maks. 3 znaki od
dokładnie jednego zwróconego wyniku tego samego typu (wyraźnie bliższego niż pozostałe) mapuje na
ten wynik. Zamiast URI model może podać dokładną nazwę utworu z wyniku.

Czwarty przebieg (13:03, nowy Core): procedura przeszła poprawnie, play z URI z liked na lokalny
PC został przyjęty, a current pokazał właściwy utwór (progressMs=1694), ale isPlaying=false.
Spotify Connect po transferze urządzenia czasem ładuje utwór i zostawia go wstrzymanego. Windows:
jeśli po oczekiwaniu na tym PC jest załadowany dokładnie wybrany URI/kontekst, ale wstrzymany,
wysyłane jest jedno wznowienie (play bez body na ten deviceId) i stan jest obserwowany ponownie
(observedPlayback.resumedLoadedItem=true). Nigdy nie wznawia innego utworu ani urządzenia.

## Właściwa przyczyna braku odtwarzania (2026-10-11, test na żywo)

Na komputerze użytkownika (Spotify z Microsoft Store, DESKTOP-LGMV8BS) odtworzono problem
bezpośrednio przez PcSpotify:
- PUT me/player/play z {"uris":[track]} zwraca 204, ale aplikacja desktopowa porzuca utwór:
  item=null, isPlaying=false, actions.disallows resuming/pausing/transferring_playback.
  Komenda potrafi też „zepsuć” grający odtwarzacz. Transfer z play=true, wznowienie, przycisk
  play z sesji multimediów Windows i wyłączenie shuffle tego nie naprawiają.
- Ten sam utwór jako {"context_uri": album, "offset": {"uri": track}} gra natychmiast; album jako
  context_uri także. Sprawdzone również ze stanu zepsutego przez wcześniejsze „uris”.

Windows: play pojedynczego utworu pobiera teraz album (GET tracks/{id}?market=from_token) i startuje
utwór w kontekście albumu z offsetem. Jeśli albumu nie da się ustalić, zostaje dotychczasowe „uris”.
Weryfikacja nadal porównuje item.uri z wybranym utworem. Test na żywo: observedPlayback
isPlaying=true, właściwy URI i lokalny deviceId po jednym wywołaniu play.

Utwór z polubionych gra teraz w kontekście „Polubionych utworów”, żeby kolejka leciała dalej
zamiast kończyć się na jednym singlu (kontekst albumu). Core dopisuje context=liked do play,
gdy wybrany URI pochodzi z wyniku liked. Windows startuje wtedy
spotify:user:<id>:collection z offsetem na ten utwór. Przy błędzie wraca do kontekstu albumu.
Test na żywo: isPlaying=true z właściwym URI, a kolejka zawiera następne utwory. Przy włączonym
Smart Shuffle Spotify raportuje kontekst jako swoją playlistę 37i9dQZF1F5….

## Czas odpowiedzi: jedno narzędzie pc__spotify_play dla AI (2026-10-11)

Udane przebiegi trwały ok. 3 minut. Czas szedł na lokalny model, nie na Spotify: osobna decyzja
wstępna („czy potrzebuję narzędzia”), a potem długie myślenie przed każdym z ok. 8–10 kroków.
Próbna wersja, w której Core wykonywał ogólne prośby bez modelu, trwała 8 s, ale omijała AI.
Zastąpiło ją narzędzie, w którym AI decyduje, a Core tylko wykonuje.

- pc__spotify_play (PcTool SPOTIFY_PLAY, wykonywane wyłącznie przez NativeToolLoopService/SpotifyAutopilot):
  model wybiera source=liked|search, query, type, pick=random|first oraz device (puste = ten PC).
  Core wykonuje procedurę jako zwykłe wywołania pc SPOTIFY/OPEN zapisane w krokach pętli:
  workflow (jeśli jeszcze nieodczytany) → status → devices → [OPEN + do 3 odczytów devices] →
  liked/search → play (context=liked dla polubionych) → do 2 odczytów current. Każde wywołanie przechodzi
  przez SpotifyPlaybackPolicy.guard. Wynik dla modelu zawiera title, artists, device i confirmed
  albo dokładny problem (NOT_CONNECTED, NO_DEVICE z listą widocznych urządzeń, NOTHING_FOUND…).
- Odpowiedź: model odpowiada własnymi słowami (SpotifyPlaybackPolicy.groundedAnswer), jeśli
  odtwarzanie jest potwierdzone, odpowiedź wymienia grający tytuł i nie zawiera linków ani URI.
  W przeciwnym razie trafia odpowiedź oparta na dowodach.
  Gdy nic nie gra, przechodzi ostatnia własna wypowiedź modelu (lastModelContent, nie ogólny
  komunikat Core), ale tylko jeśli wprost przyznaje niepowodzenie („nie widzę telewizora”) i nie
  zawiera twierdzeń o odtwarzaniu ani linków. Decyzja wstępna modelu zostaje: najpierw AI, potem narzędzia.
- Inne urządzenia: dozwolone tylko wtedy, gdy wiadomość użytkownika je wskazuje nazwą albo rodzajem
  (telewizor/tv, głośnik/soundbar, telefon…). Same argumenty modelu nigdy tego nie autoryzują.
  Windows przyjmuje takie urządzenie wyłącznie po dokładnym deviceId i nigdy, gdy to inny komputer.
- Ograniczenie Spotify: Web API (me/player/devices) pokazywało tylko DESKTOP-LGMV8BS, choć menu
  „Podłącz do urządzenia” w aplikacji pokazuje TV „FrytaCraft.pl”. Urządzenia wykryte przez aplikację
  w sieci lokalnej (np. Cast) API widzi dopiero, gdy są aktywne na koncie. Wtedy narzędzie
  zwraca NO_DEVICE z listą widocznych urządzeń zamiast zgadywać.
- Log: [SPOTIFY_PLAY] requestId=… arguments=… confirmed=… errorCode=… elapsedMs=….

### Telewizor i przenoszenie odtwarzania (test na żywo)

- TV „FrytaCraft.pl” nie jest zalogowany na koncie. Aplikacja na PC/telefonie wykrywa go w sieci
  lokalnej. Do Web API trafia dopiero po wybraniu go raz w aplikacji Spotify w trybie Spotify Connect.
  Potem był widoczny jako typ TV także wtedy, gdy grał komputer. Nie wiadomo, jak długo zostaje na liście.
- Odtwarzanie na TV przez play z jawnym deviceId działa (isPlaying=true na FrytaCraft.pl).
- source=current przenosi to, co gra: Windows play z transfer=true wysyła PUT me/player
  {device_ids, play:true}, czyli natywny transfer Spotify z tym samym utworem, pozycją, kolejką i Smart
  Shuffle. Target służy tylko do weryfikacji. Testy TV→PC i PC→TV: ten sam URI, ciągła pozycja.
  Wcześniejsza próba „kontekst + offset” zawodziła przy Smart Shuffle (polecany utwór nie należy do
  polubionych), dlatego jej nie użyto.
- Polityka uznaje utwór z odczytu current wykonanego przed play za rzeczywiste źródło.

### pc__spotify_control: szybkie sterowanie bieżącym odtwarzaniem

- „Następna” trwała 50 s, bo szła starą drogą krok po kroku. Polityka odtwarzania traktowała
  „puść następną” jak nowe odtwarzanie i nie dopuszczała next.
- Nowe narzędzie pc__spotify_control (PcTool SPOTIFY_CONTROL, wykonywane w pętli przez SpotifyAutopilot):
  action=next|previous|pause|resume|volume|shuffle|repeat (+value/state). Core czyta current, wysyła
  komendę na urządzenie, które właśnie gra (PC albo TV), i do 3 razy sprawdza, czy odtwarzacz się zmienił
  (inny utwór, pauza, wznowienie). Nie czyta workflow ani listy urządzeń.
- Polityka: udane pc__spotify_control kończy prośbę (assess ok). Odpowiedź modelu przechodzi, a przy
  niepowodzeniu tylko jako jawne przyznanie problemu.
- Błąd klienta Windows: next/shuffle odpowiadają 200 z gołym tekstem (identyfikator kolejki) zamiast JSON.
  Parser rzucał wyjątek raportowany jako „Spotify connection failed”, choć komenda się wykonała.
  Teraz odpowiedź niebędąca JSON-em jest traktowana jako sukces bez danych. Test na żywo: next przyjęty,
  zmiana utworu potwierdzona.

### Przebieg 15:11–15:14: urządzenie z wiadomości użytkownika i prawdziwość odpowiedzi

- „A odpal to na telewizorze”: model wywołał pc__spotify_play bez device i z source=liked, więc nowy utwór zagrał
  na PC. Teraz urządzenie wskazane w wiadomości użytkownika (nazwa albo rodzaj, dokładnie jedno widoczne)
  wygrywa z brakiem device albo z wyborem tego PC (SpotifyAutopilot.device i prepare). „to/tego/this/it/przenieś”
  przy innym urządzeniu oznacza przeniesienie bieżącego utworu (source=current).
- „I włączy to na TV”: grał PC, a odpowiedź twierdziła, że TV. groundedAnswer sprawdzał tylko tytuł.
  Teraz odrzuca odpowiedź, która wymienia inne widoczne urządzenie (nazwę albo rodzaj) niż faktycznie grające.
- „next”: wiadomość bez słowa „spotify” omijała sprawdzanie odpowiedzi, a model podał stary tytuł.
  Odpowiedź jest teraz sprawdzana zawsze, gdy w żądaniu wykonano pc__spotify_play/control. Udany control
  wymaga, żeby odpowiedź wymieniała nowy tytuł, inaczej trafia wynik narzędzia.
- Na żywo o 15:2x: API widziało FrytaCraft.pl jako nieaktywne. Transfer na TV dał isPlaying=true na TV,
  utrzymane przez 9 s.

## Komplet narzędzi i automatyczne ładowanie Spotify.md (2026-10-11)

- pc__spotify_play: source=liked|search|random|library|current, query, type, owner (autor playlisty),
  pick, mode=play|queue, device. random to wyszukiwanie losowej litery na losowej głębokości katalogu
  (Spotify nie ma endpointu „losowy utwór”). library to playlisty użytkownika dopasowane po nazwie.
  mode=queue dodaje utwór do kolejki urządzenia, które gra.
- pc__spotify_control: now (co gra, tylko odczyt), like (zapis bieżącego utworu do polubionych), next,
  previous, pause, resume, volume, shuffle, repeat.
- Błąd klienta Windows: market=from_token przy search dawał 403 „Insufficient client scope” (wymaga
  user-read-private), więc każde wyszukiwanie z poprzedniej poprawki zawodziło. market zostaje tylko
  przy polubionych/bibliotece, gdzie działa. Na żywo: wyszukiwanie playlist z autorami i utworów działa.
- KnowledgeRetrievalStage + WorkflowRouter (jarvis-knowledge): przed pierwszym wywołaniem modelu Core
  wybiera jeden workflow. findWorkflows (hybryda BM25 + embeddingi) daje kolejność, a bramka wymaga,
  żeby wiadomość zawierała tag, alias albo słowo z tytułu dokumentu (5-literowy rdzeń na polską odmianę).
  Krótkie dopytania (≤4 słowa: „next”, „co to za piosenka?”) są łączone z dwiema poprzednimi wiadomościami
  użytkownika. Dokument trafia w całości do kontekstu przez istniejący DefaultContextBuilder: do decyzji
  modelu i do pętli narzędzi. Log: [KNOWLEDGE_PRELOAD]. W trybie LEGACY nic się nie zmienia.
- Nowy docs/vault/workflows/Spotify.md (do skopiowania do vault na Ubuntu): tabela „prośba → narzędzie
  → argumenty” oraz tagi i aliasy pod routing (piosenka, kolejka, głośność, telewizor…).

### Przebieg 15:44: „wrzuć to na telewizor”

- Decyzja wstępna wskazała dobrze (source=current, device=telewizor), ale w pętli model użył
  niskopoziomowego pc__spotify: devices, opis wywołania tekstem (JSON) zamiast tool call, a po „kontynuuj”
  wymyślona akcja transfer („Unsupported Spotify action”). Na koniec do użytkownika trafiła instrukcja JSON,
  bo bez wywołania narzędzia złożonego odpowiedź nie była sprawdzana.
- SpotifyAutopilot.redirect: wywołanie pc__spotify, które steruje odtwarzaniem, Core zamienia na narzędzie
  o tym samym znaczeniu: next/previous/pause/volume/shuffle/repeat oraz play bez target (wznowienie) idą do
  pc__spotify_control, a transfer/move albo play z transfer=true do pc__spotify_play source=current z
  urządzeniem z deviceId/deviceName. Log: [SPOTIFY_REDIRECT].
- „wrzuć/przenieś/przerzuć/zapuść” rozpoznawane jako prośby o odtwarzanie. requestedDevice dopasowuje też po
  dokładnym id. Opis pc__spotify w schemacie wskazuje narzędzia złożone.
- „Z telewizora na komputer”: gdy wiadomość wymienia też ten PC (komputer/pc/laptop/nazwa), Core nie narzuca
  urządzenia z wiadomości. Nadpisanie działa tylko wtedy, gdy model w ogóle nie podał urządzenia.

### Przebieg 15:56: „wrzuć to na tv” — zagrało, ale „nieukończone”

- Przeniesienie zadziałało, ale przy Smart Shuffle TV zaczął inny utwór z kolejki („Alien” zamiast „Nagano 98”,
  sprawdzone na żywo). Weryfikacja wymagała tego samego URI, więc uznała przeniesienie za nieudane.
- Dla transfer=true potwierdzeniem jest teraz isPlaying=true na wybranym urządzeniu. Odpowiedź podaje
  faktycznie grający utwór: „Przeniesiono odtwarzanie Spotify na <urządzenie>. Gra: <tytuł>”. Klient Windows
  przy transferze czeka na granie na urządzeniu, a nie na konkretny URI, i raz wznawia, jeśli sesja dotarła na pauzie.

## Analiza śladu rozmowy 88d0c119 (17:22–17:30)

- W każdym zapytaniu z narzędziem pierwsza tura pętli czekała 16,3–17,5 s na pierwszy token
  (toolDefinitions=125). Kolejne tury trwały 0,6–2,4 s (prompt w cache Ollamy). Decyzja wstępna to 4,7–6,6 s
  (promptEvalCount ~5000), myślenie modelu łącznie 1–5 s, narzędzia Spotify 0,1–3 s. Prompt pętli jest inny niż
  prompt decyzji wstępnej, więc cache nie przetrwa między wiadomościami i 125 definicji liczy się od zera.
- Zmiana: ToolCallingStage przekazuje w kontekście listę narzędzi wymienionych w workflow załadowanym przez
  router (workflowTools, wyciągane z treści wiedzy wzorcem nazwa__operacja). NativeToolLoopService oferuje wtedy
  tylko te narzędzia oraz knowledge__*. Log: [NATIVE_TOOL_SCHEMA] workflowScoped=true from=125 to=N.
  Workflow, który nie wymienia narzędzi, niczego nie ogranicza.
- „daj się skupić”, „daj pogadać”, „dobra daj się skupić xD”: workflow nie został załadowany (knowledgeContextChars=82),
  bo tych fraz nie ma w aliases/tags, tylko w tabeli. Model odpowiedział bez narzędzia. Router dopasowuje wyłącznie
  frontmatter (aliasy jako całe frazy), więc nowe frazy z tabeli trzeba też dopisać do aliases.

## Analiza śladu 536dee94 (17:38–17:44)

- Zapytania z załadowanym Spotify.md: 11–15 s (było ~27 s). 1. tura pętli czekała 3,2 s na pierwszy token przy
  21 narzędziach (było 16,5 s przy 125). Rozkład typowego zapytania: decyzja wstępna ~5 s (promptEval ~3,2 s dla
  ~5200 tokenów), 1. tura ~3,2 s + ~1 s myślenia, narzędzie 0,2–2,7 s, tura odpowiedzi ~1,5–2 s.
- „dobra zatrzymaj” 26 s i „wznów” 31 s: router nie dopasował, więc pętla dostała 125 narzędzi, a model użył
  niskopoziomowego pc__spotify (pause; play z pustymi polami, przekierowane). „jebnij basem!” poszło bez narzędzia.
- Pamięć tematu (KnowledgeRetrievalStage): wiadomość do 4 słów, która nic nie dopasowała, dziedziczy workflow z
  ostatniego dopasowania w tej rozmowie, jeśli było w ciągu 10 minut (log reason=topic).
- Przy workflowScoped pętla oferuje obok narzędzi z workflow tylko knowledge__find_workflow/read_workflow/
  read_document/search_content zamiast wszystkich knowledge__*.

### Bez decyzji wstępnej przy załadowanym workflow; odpowiedź zawsze od modelu

- ModelExecutionStage: gdy KnowledgeRetrievalStage załadował workflow (źródło z kategorią workflow) i nie ma
  obrazów, osobne wywołanie „czy potrzebuję narzędzia?” jest pomijane (ok. 5 s). Pierwsza myśl modelu powstaje
  w pętli narzędzi, która może też odpowiedzieć bez narzędzia. Log: initialModelDecision=TOOL_REQUEST
  reason=ROUTED_WORKFLOW modelCalls=0.
- Odpowiedź: gdy pierwsza odpowiedź modelu nie zgadza się z obserwowanym stanem (tytuł, urządzenie, sukces lub
  porażka), model dostaje fakty z narzędzia i pisze odpowiedź ponownie, własnymi słowami (jedno krótkie wywołanie
  bez narzędzi). Tekst z faktów jest używany tylko wtedy, gdy także ta odpowiedź jest niezgodna. Log: [SPOTIFY_ANSWER].

## Analiza śladu 3abc7765 (18:00–18:04)

- Typowe zapytania 7–10 s (bez decyzji wstępnej, 8 narzędzi, 1. token po 2,9–4,2 s).
- „odpal mi piosenkę ogórek cypisa” 14,7 s: wyszukiwanie wybrało pierwszy wynik Spotify („Niegrzeczna Studentka”
  Cypisa). Poprawiona odpowiedź brzmiała „nie mam informacji… można odtworzyć”, choć utwór już grał. Teraz wyniki
  wyszukiwania są sortowane według liczby słów z prośby obecnych w tytule, wykonawcach i albumie (5-literowy rdzeń).
  Przy potwierdzonym odtwarzaniu odpowiedź przyznająca porażkę jest odrzucana. Prompt poprawki mówi wprost, że fakty
  opisują to, co już się stało.
- „yhm xD dobra te też jest spoko daj mu!” 20,4 s: model zinterpretował to jako tytuł („Yhmm”) zamiast głośności.
  To błąd interpretacji modelu. Pomoże wyraźniejszy opis w Spotify.md.
- „nie no chciałem kici kici wrona” 42,3 s: 5 słów, więc bez pamięci tematu, 125 narzędzi i niskopoziomowe pc__spotify.
  Pamięć tematu działa teraz do 10 słów, ale oznacza routing jako „topic”. Wtedy decyzja wstępna zostaje, a narzędzia
  są zawężane tylko wtedy, gdy cel modelu z tej decyzji zawiera słowo z tytułu workflow (np. „Spotify”). Bezpośrednie
  dopasowanie („matched”) nadal pomija decyzję wstępną.

## Analiza śladu d8ab94c4 (18:13–18:16)

- Wszystkie zapytania 8–13 s. Część odpowiedzi była jednak tekstem awaryjnym („Potwierdzono odtwarzanie…”,
  „Spotify volume done on…”, „Now playing…”), a nie słowami modelu. Przyczyny:
  - gpt-oss pisze niełamliwe spacje i myślniki (U+00A0, U+2011), więc porównanie tytułu po znakach odrzucało dobre
    odpowiedzi. Tytuł jest teraz porównywany wyłącznie po literach i cyfrach;
  - przy volume/pause/like/shuffle/repeat wymagałem tytułu utworu. Teraz tytuł jest wymagany tylko przy
    now/next/previous/resume i przy odtwarzaniu;
  - wzorzec „przyznaje porażkę” łapał „nie ma problemu”, teraz jest zawężony do konkretnych zwrotów.
- Tekst awaryjny dla sterowania jest po polsku (np. „Ustawiono głośność na 100%.”).
- Diagnostyka: zdarzenie RESPONSE_GROUNDING/SPOTIFY_ANSWER_CHECK w śladzie zawiera pierwszą odpowiedź, powód
  odrzucenia, poprawkę i powód jej odrzucenia. Log: [SPOTIFY_ANSWER] firstReplyRejected="…" rewriteRejected="…".
- Interpretacja modelu: „daj mu!” → action=now zamiast głośności; „dobra daj się skupić” → wyszukanie „focus music”
  zamiast ściszenia; „odpal to proszę” po wyszukaniu „Pan Ogórek” → losowy utwór z polubionych. Do poprawy w Spotify.md.
