"""Jarvis voice server - speech for Jarvis Core, OpenAI-compatible endpoints.

  POST /v1/audio/transcriptions   speech -> text (faster-whisper)
  POST /v1/audio/speech           text -> WAV   (engine: edge | piper | xtts)
  GET  /v1/voices                 voices of the active engine
  GET  /health

Configuration (environment, see jarvis-voice.env):
  JV_STT_MODEL     faster-whisper model: small | medium | large-v3 | turbo ...   (default: medium)
  JV_STT_DEVICE    auto | cuda | cpu                                            (default: auto)
  JV_LANGUAGE      default language                                             (default: pl)
  JV_TTS_ENGINE    edge  - Microsoft neural voices, very natural, needs internet (default)
                   piper - offline neural voices (.onnx files in JV_VOICES_DIR)
                   xtts  - your own cloned voice from a short recording (GPU recommended)
  JV_VOICE         default voice: edge name (pl-PL-MarekNeural), piper model file name
                   (pl_PL-darkman-medium) or xtts sample file name (jarvis.wav)
  JV_VOICES_DIR    folder with piper .onnx models and xtts voice samples   (default: ./voices)
  JV_EDGE_PITCH    e.g. -5Hz for a deeper edge voice                    (default: +0Hz)
"""
import asyncio
import glob
import io
import logging
import os
import subprocess
import tempfile
import threading
import wave

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse, Response

LOG = logging.getLogger("jarvis-voice")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

LANGUAGE = os.getenv("JV_LANGUAGE", "pl")
VOICES_DIR = os.path.abspath(os.getenv("JV_VOICES_DIR", os.path.join(os.path.dirname(__file__), "voices")))
ENGINE = os.getenv("JV_TTS_ENGINE", "edge").strip().lower()
DEFAULT_VOICES = {"edge": "pl-PL-MarekNeural", "piper": "pl_PL-darkman-medium", "xtts": "jarvis.wav", "test": "test"}
DEFAULT_VOICE = os.getenv("JV_VOICE", "").strip() or DEFAULT_VOICES.get(ENGINE, "")

app = FastAPI(title="Jarvis voice server")
_lock = threading.Lock()
_cache = {}


# ---------------------------------------------------------------- speech to text

def _whisper():
    with _lock:
        if "whisper" not in _cache:
            from faster_whisper import WhisperModel
            name = os.getenv("JV_STT_MODEL", "medium")
            device = os.getenv("JV_STT_DEVICE", "auto")
            try:
                model = WhisperModel(name, device=device, compute_type="default")
            except Exception as error:  # missing CUDA libraries -> CPU still works
                LOG.warning("Whisper on %s failed (%s), using CPU", device, error)
                model = WhisperModel(name, device="cpu", compute_type="int8")
            _cache["whisper"] = model
            LOG.info("Whisper model %s loaded", name)
        return _cache["whisper"]


def transcribe_file(path: str, language: str) -> str:
    if os.getenv("JV_STT_MODEL") == "test":
        return "test transkrypcji %d bajtów" % os.path.getsize(path)
    try:
        segments, _ = _whisper().transcribe(path, language=language or None, vad_filter=True, beam_size=5)
        return " ".join(segment.text.strip() for segment in segments).strip()
    except Exception as error:
        if not any(word in str(error).lower() for word in ("cuda", "cudnn", "cublas")):
            raise
        LOG.warning("Whisper GPU failed while transcribing (%s) - switching to CPU", error)
        from faster_whisper import WhisperModel
        with _lock:
            _cache["whisper"] = WhisperModel(os.getenv("JV_STT_MODEL", "medium"), device="cpu", compute_type="int8")
        segments, _ = _cache["whisper"].transcribe(path, language=language or None, vad_filter=True, beam_size=5)
        return " ".join(segment.text.strip() for segment in segments).strip()


@app.post("/v1/audio/transcriptions")
async def transcriptions(file: UploadFile = File(...), model: str = Form(""), language: str = Form(""),
                         response_format: str = Form("json")):
    data = await file.read()
    if not data:
        raise HTTPException(400, "empty audio")
    suffix = os.path.splitext(file.filename or "speech.wav")[1] or ".wav"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as handle:
        handle.write(data)
        path = handle.name
    try:
        text = await asyncio.to_thread(transcribe_file, path, language or LANGUAGE)
    finally:
        os.unlink(path)
    LOG.info("transcribed %d bytes -> %d chars", len(data), len(text))
    if response_format == "text":
        return Response(text, media_type="text/plain; charset=utf-8")
    return {"text": text}


# ---------------------------------------------------------------- text to speech

def _wav_bytes(samples_int16: bytes, rate: int) -> bytes:
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(samples_int16)
    return buffer.getvalue()


async def speak_edge(text: str, voice: str, speed: float) -> bytes:
    import edge_tts
    rate = "%+d%%" % round((speed - 1.0) * 100)
    pitch = os.getenv("JV_EDGE_PITCH", "+0Hz")
    with tempfile.TemporaryDirectory() as tmp:
        mp3 = os.path.join(tmp, "speech.mp3")
        wav = os.path.join(tmp, "speech.wav")
        await edge_tts.Communicate(text, voice, rate=rate, pitch=pitch).save(mp3)
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", mp3, "-ac", "1", "-ar", "24000", wav], check=True)
        with open(wav, "rb") as handle:
            return handle.read()


def speak_piper(text: str, voice: str, speed: float) -> bytes:
    from piper import PiperVoice
    from piper.config import SynthesisConfig
    model_path = voice if voice.endswith(".onnx") else os.path.join(VOICES_DIR, voice + ".onnx")
    with _lock:
        if model_path not in _cache:
            if not os.path.exists(model_path):
                raise HTTPException(404, "Piper voice not found: %s (download it, see README)" % model_path)
            _cache[model_path] = PiperVoice.load(model_path, use_cuda=os.getenv("JV_PIPER_CUDA") == "1")
        piper_voice = _cache[model_path]
    buffer = io.BytesIO()
    with wave.open(buffer, "wb") as out:
        piper_voice.synthesize_wav(text, out, syn_config=SynthesisConfig(length_scale=1.0 / max(0.5, min(speed, 2.0))))
    return buffer.getvalue()


def speak_xtts(text: str, voice: str, speed: float) -> bytes:
    os.environ.setdefault("COQUI_TOS_AGREED", "1")
    sample = voice if os.path.isabs(voice) else os.path.join(VOICES_DIR, voice)
    if not os.path.exists(sample):
        raise HTTPException(404, "Voice sample not found: %s - record 10-30 s of clean speech as a WAV there" % sample)
    with _lock:
        if "xtts" not in _cache:
            import torch
            from TTS.api import TTS
            device = "cuda" if torch.cuda.is_available() else "cpu"
            _cache["xtts"] = TTS("tts_models/multilingual/multi-dataset/xtts_v2").to(device)
            LOG.info("XTTS loaded on %s", device)
        tts = _cache["xtts"]
        extra = {} if abs(speed - 1.0) < 0.01 else {"speed": speed}
        samples = tts.tts(text=text, speaker_wav=sample, language=LANGUAGE, **extra)
    import numpy
    pcm = (numpy.clip(numpy.asarray(samples, dtype="float32"), -1.0, 1.0) * 32767).astype("<i2").tobytes()
    return _wav_bytes(pcm, 24000)


def speak_test(text: str, voice: str, speed: float) -> bytes:
    return _wav_bytes(b"\x00\x00" * 2400, 24000)  # 0.1 s of silence


@app.post("/v1/audio/speech")
async def speech(request: dict):
    text = str(request.get("input", "")).strip()
    if not text:
        raise HTTPException(400, "input is empty")
    voice = str(request.get("voice") or "").strip() or DEFAULT_VOICE
    speed = float(request.get("speed") or 1.0)
    if ENGINE == "edge":
        audio = await speak_edge(text, voice, speed)
    elif ENGINE == "piper":
        audio = await asyncio.to_thread(speak_piper, text, voice, speed)
    elif ENGINE == "xtts":
        audio = await asyncio.to_thread(speak_xtts, text, voice, speed)
    elif ENGINE == "test":
        audio = speak_test(text, voice, speed)
    else:
        raise HTTPException(500, "Unknown JV_TTS_ENGINE %s (use edge, piper or xtts)" % ENGINE)
    LOG.info("spoke %d chars with %s/%s -> %d bytes", len(text), ENGINE, voice, len(audio))
    return Response(audio, media_type="audio/wav")


@app.get("/v1/voices")
async def voices():
    if ENGINE == "edge":
        import edge_tts
        found = await edge_tts.list_voices()
        return {"engine": "edge", "default": DEFAULT_VOICE,
                "voices": sorted(v["ShortName"] for v in found if v["Locale"].startswith(LANGUAGE) or "Multilingual" in v["ShortName"])}
    if ENGINE == "piper":
        names = [os.path.basename(p)[:-5] for p in glob.glob(os.path.join(VOICES_DIR, "*.onnx"))]
        return {"engine": "piper", "default": DEFAULT_VOICE, "voices": sorted(names)}
    if ENGINE == "xtts":
        names = [os.path.basename(p) for p in glob.glob(os.path.join(VOICES_DIR, "*.wav"))]
        return {"engine": "xtts", "default": DEFAULT_VOICE, "voices": sorted(names)}
    return {"engine": ENGINE, "default": DEFAULT_VOICE, "voices": [DEFAULT_VOICE]}


@app.get("/health")
async def health():
    return JSONResponse({"ok": True, "tts": ENGINE, "voice": DEFAULT_VOICE, "stt": os.getenv("JV_STT_MODEL", "medium"),
                         "language": LANGUAGE})


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=os.getenv("JV_HOST", "127.0.0.1"), port=int(os.getenv("JV_PORT", "8100")))
