# Jarvis voice server

Speech recognition (faster-whisper) and Jarvis's voice (Edge neural voices, offline Piper, or your own
cloned voice with XTTS) behind OpenAI-compatible endpoints used by Jarvis Core (`jarvis.voice.*`).

Install on Ubuntu: `sudo ./install.sh [--gpu] [--piper] [--xtts]`, then set in Core
`JARVIS_VOICE_STT_URL=http://127.0.0.1:8100` and `JARVIS_VOICE_TTS_URL=http://127.0.0.1:8100`.

Full guide (Polish): [docs/VOICE_PL.md](../../docs/VOICE_PL.md). Settings: `/etc/jarvis-voice.env`.
