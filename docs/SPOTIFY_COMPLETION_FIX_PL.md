# Poprawka zakończenia odczytów Spotify

## Diagnoza i zakres

Kontrolowany model odtwarza mechanizm: stara bramka READ_RETRY_PERMISSION_QUESTION_NOT_COMPLETE
szukała ogólnych propozycji („Czy mam”, „would you like me to”) i „try again” także w rozumowaniu.
Odrzucała prawidłowy wynik status/devices. Kolejne krótkie odpowiedzi uruchamiały
NO_NATIVE_TOOL_CALL_PROGRESS. Nie analizowano logów Ubuntu dla requestu
e9f31dd2-6c56-4b2c-9a88-3b691eb0968b; nie jest to potwierdzenie przebiegu tego requestu.
Klasyfikacja SPOTIFY jako UNKNOWN była osobną niedokładnością, a nie bezpośrednią przyczyną.

Zachowano rozpoczętą klasyfikację pc/SPOTIFY i pc/MEDIA według action, przenoszenie argumentów
w krokach pętli i rozdzielenie widocznej odpowiedzi od rozumowania. Bramka ponowienia wymaga
teraz widocznego pytania o próbę oraz nieudanego odczytu/wyszukiwania/inspekcji/weryfikacji.
Nie traktuje błędu operacji zmieniającej stan jako zgody na jej automatyczne powtórzenie.

Obie regresje Roblox wynikały z udostępniania zapamiętanego, odrzuconego tekstu po zatrzymaniu
pętli: pytania o ponowienie oraz przyznania braku danych. Usunięto tę ścieżkę publikowania
niezweryfikowanych odpowiedzi. Wyjątek stanowi odpowiedź wstrzymana wyłącznie przez plan:
może wrócić dopiero po przejściu bramek dowodów i kontraktu celu, z wyraźnym oznaczeniem
„zadanie nieukończone / częściowa odpowiedź” i flagami completed=false, goalSatisfied=false.
Brak dowodów, bootstrap z przyznaniem niewystarczalności i same nieudane odczyty nie kończą
się sukcesem. Nie zmieniono oczekiwań istniejących testów Roblox ani limitów tur.

PcTool raportuje connected=false jako wynik poprawnego odczytu, a pustą listę jako zero
urządzeń zwróconych przez Spotify. Nie zgaduje przyczyny pustej listy. Status/devices nie
uruchamiają connect ani play. Ta poprawka nie wymaga zmian repo Windows.
verificationPerformed nadal oznacza istniejące role VERIFY/EXECUTE; sam READ może mieć
completed=true i goalSatisfied=true przy verificationPerformed=false.

## Uzupełnienie po teście requestu 458e725f-55bf-4c42-b492-6f4226845c22

Raport z Ubuntu wykazał brak action=devices po udanym action=status. Dotychczasowy kontrakt
uznawał dowolny wynik narzędzia i niepustą odpowiedź za wystarczające, nie przypisując dowodów
do osobnych części celu. Zmiana klasyfikacji status na READ nie rozwiązywała tej luki.

Dodano deterministyczny kontrakt dla złożonego pytania o połączenie Spotify i urządzenia
(rozpoznawanie polskich i angielskich określeń w oryginalnym poleceniu, niezależnie od zawężonego
podcelu modelu). Kontrakt wymaga osobno:

- udanego pc/SPOTIFY action=status z polem connected typu boolean;
- udanego pc/SPOTIFY action=devices z listą urządzeń, również pustą.

Sam status, hint, devices umieszczone w odpowiedzi status, nieudany odczyt ani brak pola devices
nie spełniają drugiego kryterium. Bramka obejmuje zwykły finał, długą odpowiedź po wyczerpaniu
prób zakończenia oraz pustą odpowiedź kierowaną do syntezy. Przy brakującym wyniku model
otrzymuje wskazanie brakującego odczytu w istniejącym budżecie; jeżeli go nie wykona, wynik
pozostaje nieukończony. Nie zwiększono limitów.

Dla tego złożonego pytania końcowe podsumowanie powstaje z danych narzędzi, a nie swobodnego
tekstu modelu. Nazwy i typy urządzeń pochodzą tylko z action=devices. connected=true oznacza
„Spotify jest połączone”, a nie „nawiązano połączenie”. Brakujące odczyty są jawnie wymienione,
również w awaryjnych ścieżkach zakończenia. connected=false jest poprawnym wynikiem statusu;
nie zastępuje brakującego wyniku devices. Nie dodano automatycznego connect/play.

Testy obejmują zgłoszony przebieg status → niepotwierdzone urządzenie, uzupełnienie devices
po odrzuceniu odpowiedzi, zmyśloną nazwę mimo devices=[], odwrotnie brakujący status,
nieudany/malformed devices, connected=false bez devices, puste i długie finały oraz fałszywe
pole devices w odpowiedzi status. Poprzednie asercje braku sukcesu zachowano; asercje dokładnego
tekstu udanego finału dostosowano do podsumowania z dowodów zamiast słów modelu.

Walidacja uzupełnienia: pełne `mvn -o -pl jarvis-core -am verify` — BUILD SUCCESS,
802 testy, zero failures/errors/skipped (428 tools, 83 core). Zestaw Spotify obejmuje 17 testów.

To kontrakt dla wskazanego złożonego odczytu Spotify, nie ogólny semantyczny walidator każdego
możliwego celu wieloczęściowego. Pozostałe cele zachowują dotychczasową walidację.

## Osobny problem: routing i workflow (propozycja, bez implementacji)

NativeToolLoopService.systemPrompt buduje własny prompt; nie używa request.basePrompt.
Zmiany samego jarvis.md nie trafiają więc bezpośrednio do modelu pętli.
Nie należy doklejać całego promptu do każdej iteracji.

Proponowany krótki blok zasad przekazywany przy inicjalizacji pętli:

- Do Spotify na komputerze używaj pc__spotify. Status połączenia: action=status;
  urządzenia: action=devices. Do tych odczytów nie używaj przeglądarki ani wyszukiwania WWW.
- success=true oznacza udany odczyt. connected=false i devices=[] są ważnymi wynikami.
- Sam odczyt nie upoważnia do connect, disconnect ani sterowania odtwarzaniem.
  Zakaz odtwarzania z polecenia użytkownika obowiązuje przez całą pętlę.
- Gdy zadanie wymaga szczegółów integracji, odczytaj workflows/Spotify.md z vault
  przez knowledge__read_document. Przeczytaj na żądanie raz i zachowaj wynik w kontekście.

Blok powinien być małym, jawnie przekazywanym zestawem reguł routingu, testowanym osobno.
Bieżąca konfiguracja Ubuntu wskazana przez użytkownika to
JARVIS_AI_IDENTITY_FILE=file:/opt/jarvis/config/jarvis.vault-test.md oraz
/opt/jarvis/knowledge/workflows/Spotify.md. Lokalny config/jarvis.md nie potwierdza ich treści.

## Walidacja lokalna (2026-10-10)

- Przed poprawką: 18 testów Spotify/Roblox, dokładnie dwie wskazane porażki Roblox.
- Po poprawce: pełne `mvn -o -pl jarvis-core -am verify`, BUILD SUCCESS.
- 792 testy: 418 tools, 83 core, 39 knowledge, 134 memory, 48 api, 46 ollama,
  17 common, 7 brain-router. Zero failures/errors/skipped.
- Testy obejmują krótką odpowiedź, propozycję dalszej akcji, „try again” wyłącznie w rozumowaniu,
  rzeczywiste pytanie po błędzie, brak dowodów, connected=false, devices=[], komunikaty PcTool,
  klasyfikację odczytów/wyszukiwania/mutacji i częściowy wynik z nieukończonego planu.
- JDK 21.0.9, Maven 3.8.5. Testy wymagające loopback uruchomiono poza sandboxem;
  `-DargLine=-Djava.io.tmpdir=D:/J.AR.V.I.S/test-tmp` omija ograniczenia przenoszenia plików
  w systemowym katalogu tymczasowym sandboxa. Nie pominięto testów.

## Wdrożenie na Ubuntu — wykonuje użytkownik

Nie wykonano push ani wdrożenia. Nie zakłada się dostępu SSH.
Przenieś plik poprawki wygenerowany poleceniem `git format-patch -1 <hash-poprawki>`.
Buduj z aktualnych źródeł Ubuntu, zachowując lokalne poprawki głosu. Nie zastępuj tych źródeł
samym checkoutem b940d0c ani gotowym lokalnym JAR-em, który nie zawiera poprawek Ubuntu.

1. Ustal rzeczywisty katalog źródeł, usługę i ścieżkę JAR-a z jej konfiguracji:
   `systemctl cat NAZWA_USLUGI` i `systemctl show NAZWA_USLUGI -p ExecStart -p WorkingDirectory`.
   Sprawdź `git status`, `git diff --stat`, lokalne commity oraz pliki nieśledzone w źródłach.
   Zapisz konfigurację usługi i kopię obecnego JAR-a na HDD. Nie zmieniaj konfiguracji głosu,
   tożsamości, vault, sekretów ani plików danych.
2. Utwórz nowy katalog kompilacji na /mnt/dysk8tb. Poniższe zmienne są przykładami do uzupełnienia:

```bash
set -euo pipefail
SOURCE=/rzeczywista/sciezka/do/zrodel
SERVICE=rzeczywista-nazwa.service
LIVE_JAR=/rzeczywista/sciezka/do/aktualnego.jar
PATCH=/mnt/dysk8tb/spotify-completion.patch
BUILD=/mnt/dysk8tb/jarvis-build-$(date +%Y%m%d-%H%M%S)
mkdir -p "$BUILD"
git -C "$SOURCE" status --short > "$BUILD/source-status.txt"
git -C "$SOURCE" diff --binary HEAD > "$BUILD/local-changes.patch"
git clone --no-hardlinks "$SOURCE" "$BUILD/src"
git -C "$BUILD/src" checkout --detach "$(git -C "$SOURCE" rev-parse HEAD)"
git -C "$BUILD/src" am "$PATCH"
if test -s "$BUILD/local-changes.patch"; then
  git -C "$BUILD/src" apply --check "$BUILD/local-changes.patch"
  git -C "$BUILD/src" apply "$BUILD/local-changes.patch"
fi
```

Jeżeli wystąpi konflikt, rozwiąż go w kopii na HDD, zachowując poprawki głosu; nie kontynuuj
wdrożenia z pominiętymi zmianami. Przed buildem skopiuj potrzebne nieśledzone źródła z SOURCE
zgodnie z zapisanym statusem (diff nie obejmuje plików nieśledzonych). Nie kopiuj starych buildów.

3. Uruchom pełny build z JDK 21. Cache Maven i katalog tymczasowy również trzymaj na HDD:

```bash
mkdir -p "$BUILD/tmp" /mnt/dysk8tb/maven-cache
cd "$BUILD/src"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Djava.io.tmpdir=$BUILD/tmp"
mvn -Dmaven.repo.local=/mnt/dysk8tb/maven-cache \
  "-DargLine=-Djava.io.tmpdir=$BUILD/tmp" -pl jarvis-core -am verify
NEW_JAR="$BUILD/src/javac-output-test/jarvis-core/jarvis-core-2.24.0-SNAPSHOT.jar"
test -s "$NEW_JAR"
jar tf "$NEW_JAR" > "$BUILD/jar-contents.txt"
grep '^BOOT-INF/' "$BUILD/jar-contents.txt" > "$BUILD/boot-contents.txt"
head "$BUILD/boot-contents.txt"
```

Nie wdrażaj po nieudanych testach. POM kieruje build do javac-output-test, nie standardowego target.
Wersję/nazwę JAR-a potwierdź w swoim checkoutcie. Zadbaj o wystarczające miejsce na / na jeden nowy JAR (jeśli produkcyjny JAR
pozostaje na /); nie kasuj starego przed wykonaniem kopii.

4. Po udanym buildzie zapisz poprzedni JAR na HDD i wdroż nowy, zachowując właściciela i tryb:

```bash
BACKUP="$BUILD/previous.jar"
sudo cp -p -- "$LIVE_JAR" "$BACKUP"
sudo sha256sum "$LIVE_JAR" "$BACKUP" "$NEW_JAR"
sudo systemctl stop "$SERVICE"
sudo cp -- "$NEW_JAR" "$LIVE_JAR.new"
sudo chown --reference="$LIVE_JAR" "$LIVE_JAR.new"
sudo chmod --reference="$LIVE_JAR" "$LIVE_JAR.new"
sudo mv -- "$LIVE_JAR.new" "$LIVE_JAR"
sudo systemctl start "$SERVICE"
sudo systemctl status "$SERVICE" --no-pager
sudo journalctl -u "$SERVICE" -n 100 --no-pager
```

Te polecenia zakładają zwykły plik LIVE_JAR; jeżeli to symlink, najpierw ustal docelowy plik
przez readlink -f i świadomie wybierz miejsce podmiany. Zachowaj ścieżki BUILD/BACKUP.
Sprawdź istniejący endpoint zdrowia oraz głos i wykonaj polecenie:
„Sprawdź połączenie Spotify i pokaż dostępne urządzenia. Nie uruchamiaj muzyki.”
Oczekuj status/devices, odpowiedzi zgodnej z wynikami, bez logowania/odtwarzania,
completed=true i goalSatisfied=true po udanych odczytach. Osobno sprawdź brak połączenia
oraz pustą listę. Błąd obu odczytów ma dawać completed=false/goalSatisfied=false.

5. Powrót do poprzedniego JAR-a, bez przebudowy i bez zmiany konfiguracji:

```bash
sudo systemctl stop "$SERVICE"
sudo cp -p -- "$BACKUP" "$LIVE_JAR.rollback"
sudo mv -- "$LIVE_JAR.rollback" "$LIVE_JAR"
sudo systemctl start "$SERVICE"
sudo systemctl status "$SERVICE" --no-pager
sudo journalctl -u "$SERVICE" -n 100 --no-pager
```
