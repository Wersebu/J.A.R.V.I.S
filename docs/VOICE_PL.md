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

Od razu działa głos systemu Windows (offline). Żeby mówił po polsku, potrzebny jest polski głos:
*Ustawienia → Czas i język → Mowa → Głosy / Zarządzaj głosami → dodaj Polski (Paulina)*.
W podpowiedzi przycisku 🎤 widać, którego głosu używa.

Ładniejszy, neuronowy głos może dać serwer (sekcja niżej).

## Na serwerze: rozpoznawanie mowy (wymagane do mówienia do Jarvisa)

Windows nie ma darmowego rozpoznawania mowy po polsku, dlatego robi to Whisper na serwerze (najlepiej z GPU, obok Ollamy). Jarvis współpracuje z każdym serwerem zgodnym z API OpenAI audio (`/v1/audio/transcriptions`).

Przykład: [speaches](https://github.com/speaches-ai/speaches) (faster-whisper) w Dockerze:

```bash
docker run -d --name speaches --restart unless-stopped --gpus=all -p 8000:8000 \
  -v hf-hub-cache:/home/ubuntu/.cache/huggingface/hub \
  ghcr.io/speaches-ai/speaches:latest-cuda
# jeśli Twoja wersja wymaga pobrania modelu:
curl -X POST "http://localhost:8000/v1/models/Systran/faster-whisper-medium"
```

(Bez GPU: obraz `latest-cpu` i model `Systran/faster-whisper-small`. Szczegóły i aktualne nazwy obrazów są w README projektu speaches.)

Potem w Core (zmienne środowiskowe lub `application.yml`, sekcja `jarvis.voice`):

```
JARVIS_VOICE_STT_URL=http://localhost:8000
JARVIS_VOICE_STT_MODEL=Systran/faster-whisper-medium
JARVIS_VOICE_LANGUAGE=pl
```

Sprawdzenie: `GET /api/v1/voice/status` zwraca `"stt": true`, a podpowiedź przycisku 🎤 w aplikacji pokazuje „Mikrofon gotowy”.

Można też użyć API OpenAI (`JARVIS_VOICE_STT_URL=https://api.openai.com`, `JARVIS_VOICE_STT_MODEL=whisper-1`, `JARVIS_VOICE_STT_API_KEY=...`) — wtedy nagrania wychodzą poza Twój serwer.

## Na serwerze: głos neuronowy (opcjonalnie)

Serwer zgodny z `/v1/audio/speech` zwracający WAV, np. speaches z modelem Piper z polskim głosem. Dostępne modele/głosy pokazuje dokumentacja serwera (w speaches: `GET /v1/models`).

```
JARVIS_VOICE_TTS_URL=http://localhost:8000
JARVIS_VOICE_TTS_MODEL=<model TTS z Twojego serwera>
JARVIS_VOICE_TTS_VOICE=<głos, np. polski głos Piper>
JARVIS_VOICE_TTS_SPEED=1.0
```

Gdy `tts` jest skonfigurowany, aplikacja przełącza się na głos z serwera; bez tego używa głosu Windows.

## Komendy w stylu J.A.R.V.I.S.

- „Otwórz Spotify i puść muzykę” → `pc__open spotify`, potem klawisz multimediów play/pause.
- „Następny utwór”, „Ścisz”, „Wycisz” → `pc__media next / volume_down / mute`.
- „Otwórz YouTube”, „Otwórz ustawienia”, „Otwórz folder Pobrane” → `pc__open` (strona, `ms-settings:`, folder).
- „Znajdź opinie o … na mapach” → przeglądarka Jarvisa (`browser__*`).

Uruchamianie programów i skryptów (.exe, .bat, .ps1…) przez `pc__open` zawsze wymaga Twojej zgody na PC.
