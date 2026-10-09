# Tryb głosowy (jak J.A.R.V.I.S.)

Rozmawiasz z Jarvisem głosem, a on mówi, co robi („Szukam w internecie”, „Piszę kod”, „Otwieram Spotify”, „Włączam muzykę”) i czyta odpowiedzi.

## W aplikacji Windows

- **🔊 Głos: wł./wył.** w wierszu nad polem czatu. Prawy przycisk otwiera opcje:
  - *Mów, co robisz* — krótkie komunikaty w trakcie pracy (nie częściej niż co kilka sekund, bez powtórzeń),
  - *Czytaj odpowiedzi* — Jarvis czyta odpowiedź bez kodu, tabel i linków; długie odpowiedzi tylko z początku („Szczegóły masz na ekranie”),
  - *Rozmowa bez rąk* — po każdej odpowiedzi Jarvis sam znowu słucha.
- **🎤** obok pola wpisywania albo **Ctrl+Spacja**: mów, a nagrywanie skończy się samo po chwili ciszy (kliknięcie kończy od razu). Jeśli Jarvis właśnie mówi, milknie i słucha.
- W trakcie pracy możesz mówić dalej — wiadomość trafia do pracującego agenta (jak dopisanie tekstem). **⏹ Stop** ucisza go od razu.
- W trybie głosowym Jarvis odpowiada krótko i naturalnie, a szczegóły zostawia na ekranie.

### Głos Jarvisa

Bez serwera działa głos systemu Windows (offline, dość robotyczny). Naturalny albo własny głos daje serwer głosu (niżej). Żeby mówił po polsku, potrzebny jest polski głos:
*Ustawienia → Czas i język → Mowa → Głosy / Zarządzaj głosami → dodaj Polski (Paulina)*.
W podpowiedzi przycisku 🎤 widać, którego głosu używa.

Ładniejszy, neuronowy głos może dać serwer (sekcja niżej).

## Na serwerze (Ubuntu): serwer głosu Jarvisa

W repozytorium jest gotowy serwer `tools/voice-server`: rozpoznawanie mowy (Whisper) i głos Jarvisa — do wyboru:

| silnik | brzmienie | wymaga |
|---|---|---|
| `edge` (domyślny) | neuronowe głosy Microsoftu, bardzo naturalne: `pl-PL-MarekNeural` (męski), `pl-PL-ZofiaNeural` | internetu na serwerze |
| `piper` | dobre, w pełni offline: `pl_PL-darkman-medium` (męski), `pl_PL-gosia-medium` | nic (działa na CPU) |
| `xtts` | **Twój własny głos** — klonowany z 10–30 s nagrania (np. głos w stylu J.A.R.V.I.S.) | najlepiej GPU NVIDIA, ~2 GB pobierania |

### 1. Instalacja

```bash
cd ~/J.A.R.V.I.S/tools/voice-server        # katalog repozytorium Core na serwerze
sudo ./install.sh                           # Whisper + głosy Edge
# opcje (można łączyć):
sudo ./install.sh --gpu                     # karta NVIDIA: biblioteki CUDA dla Whispera (szybciej)
sudo ./install.sh --piper                   # polskie głosy offline (darkman, gosia)
sudo ./install.sh --xtts                    # własny, sklonowany głos
```

Skrypt instaluje pakiety (python3-venv, ffmpeg), tworzy `/opt/jarvis-voice`, usługę systemd `jarvis-voice` (port 8100, tylko lokalnie) i ustawienia `/etc/jarvis-voice.env`. Na końcu wypisuje wynik `/health`.

Pierwsze rozpoznanie mowy pobiera model Whispera (medium ≈ 1,5 GB) — trwa chwilę.

### 2. Podłącz Core

W konfiguracji Core (zmienne środowiskowe usługi Jarvisa albo `application.yml`):

```
JARVIS_VOICE_STT_URL=http://127.0.0.1:8100
JARVIS_VOICE_TTS_URL=http://127.0.0.1:8100
```

Restart Core. Sprawdzenie: `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/voice/status` → `{"stt":true,"tts":true,...}`. W aplikacji wyłącz i włącz **🔊 Głos** — podpowiedź przycisku 🎤 pokaże „Mikrofon gotowy · głos z serwera”.

(Jeśli serwer głosu stoi na innej maszynie niż Core: w `/etc/jarvis-voice.env` ustaw `JV_HOST=0.0.0.0` i podaj jej adres w `JARVIS_VOICE_*_URL`; nie wystawiaj portu 8100 do internetu.)

### 3. Wybór głosu

Edytuj `/etc/jarvis-voice.env`, potem `sudo systemctl restart jarvis-voice`:

- **Edge, męski:** `JV_TTS_ENGINE=edge`, `JV_VOICE=pl-PL-MarekNeural`; niższy głos: `JV_EDGE_PITCH=-6Hz`. Lista głosów: `curl http://127.0.0.1:8100/v1/voices`.
- **Piper offline:** `JV_TTS_ENGINE=piper`, `JV_VOICE=pl_PL-darkman-medium` (wymaga `install.sh --piper`).
- **Własny głos (XTTS):**
  1. `sudo ./install.sh --xtts --gpu`
  2. nagraj 10–30 s czystej mowy (bez muzyki i szumu, jeden mówca), zapisz jako WAV: `/opt/jarvis-voice/voices/jarvis.wav`
     (z MP3: `ffmpeg -i probka.mp3 -ac 1 -ar 22050 /opt/jarvis-voice/voices/jarvis.wav`)
  3. `JV_TTS_ENGINE=xtts`, `JV_VOICE=jarvis.wav`, restart. Pierwsze uruchomienie pobiera model XTTS.

  XTTS mówi po polsku głosem z próbki. Licencja modelu XTTS (Coqui CPML) pozwala na użytek niekomercyjny; używaj nagrań, do których masz prawo.

Szybkość mowy: `JARVIS_VOICE_TTS_SPEED` w Core (np. `1.1`).

### Logi i problemy

- `journalctl -u jarvis-voice -f` — każde rozpoznanie i wypowiedź są logowane.
- Whisper wolny → `JV_STT_MODEL=small` (CPU) albo `install.sh --gpu`; błąd CUDA/cuDNN → serwer sam przełącza się na CPU (widać w logu).
- Edge nie mówi → serwer nie ma internetu; użyj `piper`.
- Zamiast tego serwera możesz użyć dowolnego serwera zgodnego z API OpenAI audio (`/v1/audio/transcriptions`, `/v1/audio/speech` zwracający WAV) — Core działa z każdym.

## Komendy w stylu J.A.R.V.I.S.

- „Otwórz Spotify i puść muzykę” → `pc__open spotify`, potem `pc__media play app=spotify` — czeka, aż Spotify będzie gotowy, i steruje właśnie Spotify (przez systemowe sterowanie multimediami Windows, jak nakładka głośności); odpowiada, co gra.
- „Następny utwór”, „Pauza”, „Co teraz leci?” → `pc__media next / pause / status app=spotify`; „Ścisz”, „Wycisz” → `volume_down / mute`.
- „Otwórz YouTube”, „Otwórz ustawienia”, „Otwórz folder Pobrane” → `pc__open` (strona, `ms-settings:`, folder).
- „Znajdź opinie o … na mapach” → przeglądarka Jarvisa (`browser__*`).

Uruchamianie programów i skryptów (.exe, .bat, .ps1…) przez `pc__open` zawsze wymaga Twojej zgody na PC.
