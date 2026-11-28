# Wdrożenie i rollback multi-engine inference

Stan 2026-11-27: nie wdrożono na produkcji. SSH do udokumentowanego `wersebu@192.168.100.10:37420` odrzuca uwierzytelnienie publickey,password. Dostęp do sudo, faktycznych ścieżek aktywnego JAR, jednostek i miejsca backupu nie został ponownie potwierdzony. Wersja i GPU produkcyjnego llama.cpp są NIEZWERYFIKOWANE. Lokalna Ollama nie jest dowodem działania serwera Ubuntu.

## Build i przygotowanie

Java 21, istniejący Maven, bez nowych zależności runtime Maven. Zbuduj `mvn -Pcompanion-integration verify` i zachowaj raporty. Produkcyjny artefakt to `javac-output-test/jarvis-core/jarvis-core-2.24.0-SNAPSHOT.jar`; nie używaj osobnego launchera companion-integration. Sprawdź manifest Start-Class=com.jarvis.core.JarvisApplication i brak com/jarvis/integration w produkcyjnym JAR. Zapisz SHA256 i commit/diff kandydata. Zmiany w tym zadaniu nie zostały automatycznie commitowane ani wysłane do zdalnego repozytorium.

## Backup przed zmianą produkcji

Po uzyskaniu autoryzowanego SSH sprawdź rzeczywiste ExecStart, WorkingDirectory, użytkownika usługi, pliki EnvironmentFile i drop-iny. Nie wypisuj sekretów. Zidentyfikuj faktyczny aktywny JAR oraz wszystkie katalogi config/data/memory/knowledge i zewnętrzne bazy z konfiguracji; nie zakładaj, że historyczne ścieżki są nadal prawidłowe. Sprawdź wolne miejsce na backup i modele.

Utwórz prywatny, datowany backup o prawach 0700 na zweryfikowanym wolnym woluminie. Zachowaj poprzedni JAR i jego SHA256, konfigurację z właścicielem/trybem, jednostki/drop-iny, źródłowy commit i lokalny diff oraz modele konfiguracji. Bazy SQLite kopiuj przez SQLite backup API z integrity_check albo po zatrzymaniu wszystkich ich writerów; nie kopiuj samego aktywnego pliku .db z pominięciem WAL. Dla PostgreSQL użyj spójnego backupu właściwej bazy. Nie zapisuj sekretów do SHARED. Dopiero zweryfikowany backup jest punktem przywrócenia. Skrypt instalacji sam nie robi backupu danych aplikacji.

## Instalacja llama.cpp

Przenieś z tego repo `scripts/install-llamacpp.sh` do `/opt/jarvis/scripts/`. Uruchom jako użytkownik usługi mający zapis do `/opt/jarvis/engines/llamacpp` i `/opt/jarvis/models/llamacpp`:

```bash
bash /opt/jarvis/scripts/install-llamacpp.sh
```

Jeśli brakuje toolkitu i operator dopuszcza instalację przez repo NVIDIA:

```bash
INSTALL_CUDA=1 bash /opt/jarvis/scripts/install-llamacpp.sh
```

Skrypt wymaga Ubuntu 22.04/24.04 x86_64, istniejącego działającego sterownika NVIDIA, NVIDIA GPU i sudo -n tylko do brakujących pakietów apt. Nie instaluje ani nie aktualizuje sterowników. Jeśli nvcc jest obecne, wymaga toolkitu >=12; nie podmienia go automatycznie. Brak toolkitu można obsłużyć jawnym INSTALL_CUDA=1, który instaluje toolkit-only cuda-toolkit-12-6 z repo NVIDIA. Niewspierany sterownik/toolkit/host compiler powinien zakończyć instalację błędem; nie wymuszaj `--allow-unsupported-compiler`.

Oficjalny release v1.1.0 wskazuje commit `074f1f3f730c1d17f34645d62c1859c74bedbdd8`; odczytany manifest latest nie zawierał gotowej binarki CUDA (jedynie nightly-tag), dlatego przygotowano kompilację z oficjalnego źródła. Pin jest stały, nie latest. Opcje: GGML_CUDA=ON, architektura CUDA 89 (RTX 4060 Ti), Release, maks. 4 równoległe zadania kompilacji. Nowszy CMake w razie potrzeby instaluje się w izolowanym venv (cmake 3.31.6). Skrypt nie uruchamia modeli ani usług.

Instalacja zapisuje `installation.json`, version.txt, help.txt, devices.txt w `/opt/jarvis/engines/llamacpp/<commit>/`, hash binarki i wersje narzędzi. Symlink current aktualizowany jest atomowo; poprzedni target zapisany w previous-target.txt. Powtórne uruchomienie przy kompletnej instalacji nie zmienia wyboru current. `--list-devices` musi wykazać CUDA przed publikacją instalacji. To sprawdza wykrycie urządzenia, nie faktyczne generowanie na GPU.

## Konfiguracja Core

Domyślnie nowy manager zachowuje Ollamę i obecny model z istniejącego ActiveModelService. Nie pobiera ponownie GPT-OSS. Przykład dodatkowej konfiguracji operatora:

```yaml
jarvis:
  inference:
    engines:
      managed-process: true
      executable: /opt/jarvis/engines/llamacpp/current/bin/llama-server
      model-directory: /opt/jarvis/models/llamacpp
      configuration-file: /opt/jarvis/config/inference-engine.json
      installer: /opt/jarvis/scripts/install-llamacpp.sh
      port: 8081
```

Core musi mieć zapis do katalogu konfiguracji i modeli oraz możliwość uruchomienia binarki/skryptu. systemd nadzoruje tylko Core; nie uruchamiaj równocześnie niezależnej jednostki llama-server. Sprawdź limity pamięci/cgroup i zachowanie zakończenia dzieci (domyślny KillMode=control-group). Instalator z UI nie eskaluje interaktywnie uprawnień: sudo -n musi być uprzednio dozwolone dla operatora, inaczej otrzymasz INSTALLATION_FAILED. Nie nadawaj szerokiego sudo tylko dla wygody przycisku UI; zależności można zainstalować osobno przez administratora systemu.

Po zweryfikowanym backupie zastąp rzeczywisty aktywny JAR kandydatem z zachowaniem właściciela/trybu. Zmiany konfiguracji i jednostek wykonaj w kontrolowanym oknie. `systemctl daemon-reload` tylko po zmianie jednostki; uruchom ponownie Core jeden raz przy wdrożeniu nowego kodu. Dalsze zmiany silnika/modelu nie wymagają restartu Core.

## Odbiór na Ubuntu

1. Sprawdź health/auth Core, starsze /api/models, rozmowę, pamięć, taski, anulowanie i STT/TTS na Ollamie.
2. Umieść istniejące GGUF/mmproj/template w katalogu bez duplikowania dużych plików. Import wymaga znanego SHA. Dla HF użyj commita i digestów osobno dla wszystkich części.
3. Odczytaj /installation i /state. Pierwsza próba llama.cpp: gpuLayers=0, contextSize=4096, parallelSlots=1. Zażądaj tylko rzeczywiście zadeklarowanych możliwości; nie włączaj vision dla zwykłego GGUF.
4. Sprawdź /health oraz /v1/models; wykonaj rzeczywisty test chat API:

```bash
python3 /opt/jarvis/scripts/smoke-llamacpp.py --generate --label warm --output llama-warm.json
```

Dopiero dla modelu z odpowiednimi możliwościami dodaj --structured, --tools, opcjonalnie --parallel. Skrypt niczego nie wykonuje jako narzędzie. Test Vision wymaga rzeczywistego obrazu i kwalifikowanego modelu/mmproj; nie jest automatycznie uruchamiany. Label cold/warm jest jawną informacją operatora; skrypt sam nie wymusza cold startu.

5. Przed GPU offload zmierz wolne VRAM z uwzględnieniem STT/TTS i wszystkich procesów. Zmierz budżet wag/KV dla wybranego modelu; ustaw umiarkowane gpuLayers i gpuBudgetMiB, zachowaj >=2048 MiB zapasu. Obserwuj `nvidia-smi --query-compute-apps=pid,process_name,used_memory --format=csv` oraz logi faktycznych offloaded layers podczas generowania. Sama obecność CUDA lub liczba tokenów nie potwierdza GPU. Zapisz pomiary z timestamp i konfiguracją.
6. Wykonaj switching → Ollama, kontrolowany nieudany load/rollback, restart Core, reconnect Windows i proces-crash/restart w środowisku testowym. Nie zabijaj procesu produkcyjnego w celu testu bez kontrolowanego okna.

## Rollback

Przy sprawnym nowym Core najpierw wybierz Ollamę i poprzedni model przez /selection; potwierdź READY, brak własnego procesu llama-server i test rozmowy. Nowe GGUF nie muszą być usuwane.

Jeśli Core nie uruchamia się: zatrzymaj jedynie właściwą jednostkę Core, zachowaj aktualny JAR/config/logi i nowe dane po wdrożeniu do osobnego katalogu. Przywróć zweryfikowany stary JAR i zgodną konfigurację z backupu, z pierwotnym właścicielem i prawami. Nowy plik inference-engine.json przenieś do prywatnego katalogu rollbacku albo przywróć zatwierdzoną poprzednią wersję; nie kasuj pamięci/historii. Nie nadpisuj baz starszym snapshotem bez analizy zapisów po wdrożeniu. Ta aktualizacja nie wprowadza destrukcyjnej migracji bazy.

Przywróć jednostki/drop-iny tylko jeśli były zmienione, wykonaj daemon-reload tylko wtedy, uruchom Core i zweryfikuj auth, health, chat, taski, pamięć i głos. Przy rollbacku samej binarki llama.cpp zatrzymaj własne dziecko przez Core i atomowo przywróć symlink current do zweryfikowanego poprzedniego katalogu; nie usuwaj modeli ani istniejącej instalacji Ollamy. Plik .bak konfiguracji jest pomocą diagnostyczną, nie zastępuje zweryfikowanego backupu produkcji.

## Ograniczenia wydania

Nie wykonano instalacji/kompilacji na Ubuntu ani testów CUDA/GPU llama.cpp z powodu braku SSH. Nie zakwalifikowano wskazanego GPT-OSS VISION. Brak uploadu GGUF z Windows, prywatnych HF tokenów, usuwania całego zestawu shardów przez API i trwałej historii operacji pobierania. Benchmark endpoint obejmuje tylko pojedynczy pomiar z obserwacją rezydencji; testy cold start/prefill/vision/równoległości wymagają dalszego benchmarkowania. Ochrona GPU ma zakres jednego Core i nie kontroluje obcych klientów Ollamy. CPU embeddingi przy llama.cpp wymagają dostępnej Ollamy. Fail closed dla specyficznego pipeline moderacji Ollamy jest celowy i wymaga osobnego zaplanowania routingu, jeśli ma działać jednocześnie z llama.cpp.
