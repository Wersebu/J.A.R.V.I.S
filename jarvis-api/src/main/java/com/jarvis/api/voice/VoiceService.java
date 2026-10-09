package com.jarvis.api.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Speech for the voice mode: speech-to-text and text-to-speech through any OpenAI-compatible audio
 * server ({@code /v1/audio/transcriptions}, {@code /v1/audio/speech}) - e.g. speaches
 * (faster-whisper + Piper/Kokoro) running next to Ollama on the server, LocalAI, or OpenAI itself.
 *
 * <p>Speech recognition needs this (Windows has no offline Polish recognizer). Speech output is
 * optional: without a TTS server the Windows app speaks with the system voice.</p>
 */
@Service
public class VoiceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoiceService.class);
    private static final int MAX_SPEAK_CHARS = 4000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper objectMapper;
    private final String sttBaseUrl;
    private final String sttApiKey;
    private final String sttModel;
    private final String language;
    private final String ttsBaseUrl;
    private final String ttsApiKey;
    private final String ttsModel;
    private final String ttsVoice;
    private final double ttsSpeed;

    /**
     * Creates the service.
     */
    public VoiceService(
            ObjectMapper objectMapper,
            @Value("${jarvis.voice.stt.base-url:}") String sttBaseUrl,
            @Value("${jarvis.voice.stt.api-key:}") String sttApiKey,
            @Value("${jarvis.voice.stt.model:Systran/faster-whisper-medium}") String sttModel,
            @Value("${jarvis.voice.language:pl}") String language,
            @Value("${jarvis.voice.tts.base-url:}") String ttsBaseUrl,
            @Value("${jarvis.voice.tts.api-key:}") String ttsApiKey,
            @Value("${jarvis.voice.tts.model:}") String ttsModel,
            @Value("${jarvis.voice.tts.voice:}") String ttsVoice,
            @Value("${jarvis.voice.tts.speed:1.0}") double ttsSpeed
    ) {
        this.objectMapper = objectMapper;
        this.sttBaseUrl = trimSlash(sttBaseUrl);
        this.sttApiKey = sttApiKey == null ? "" : sttApiKey.strip();
        this.sttModel = sttModel;
        this.language = language;
        this.ttsBaseUrl = trimSlash(ttsBaseUrl);
        this.ttsApiKey = ttsApiKey == null ? "" : ttsApiKey.strip();
        this.ttsModel = ttsModel;
        this.ttsVoice = ttsVoice;
        this.ttsSpeed = ttsSpeed;
    }

    /**
     * Returns what is configured.
     *
     * @return status map: stt, tts (booleans), language
     */
    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("stt", !sttBaseUrl.isBlank());
        status.put("tts", !ttsBaseUrl.isBlank());
        status.put("language", language);
        return status;
    }

    /**
     * Transcribes recorded speech.
     *
     * @param audio audio file bytes (WAV)
     * @param fileName file name with extension (tells the server the format)
     * @return recognized text
     */
    public String transcribe(byte[] audio, String fileName) {
        if (sttBaseUrl.isBlank()) {
            throw new VoiceException("Speech recognition is not configured on the server (jarvis.voice.stt.base-url).");
        }
        String boundary = "----jarvis" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try {
            field(body, boundary, "model", sttModel);
            if (language != null && !language.isBlank()) {
                field(body, boundary, "language", language);
            }
            field(body, boundary, "response_format", "json");
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + (fileName == null || fileName.isBlank() ? "speech.wav" : fileName) + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(audio);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(sttBaseUrl + "/v1/audio/transcriptions"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
            authorize(request, sttApiKey);
            long started = System.nanoTime();
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new VoiceException("Speech recognition failed (HTTP " + response.statusCode() + "): " + abbreviate(response.body()));
            }
            JsonNode node = objectMapper.readTree(response.body());
            String text = node.path("text").asText("").strip();
            LOGGER.info("[VOICE] transcribed audioBytes={} chars={} ms={}", audio.length, text.length(),
                    (System.nanoTime() - started) / 1_000_000);
            return text;
        } catch (IOException exception) {
            throw new VoiceException("Speech recognition server unreachable at " + sttBaseUrl + ": " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VoiceException("Interrupted", exception);
        }
    }

    /**
     * Synthesizes speech.
     *
     * @param text text to say
     * @return WAV bytes
     */
    public byte[] speak(String text) {
        if (ttsBaseUrl.isBlank()) {
            throw new VoiceException("Server speech output is not configured (jarvis.voice.tts.base-url) - "
                    + "the Windows app uses the system voice instead.");
        }
        String input = text == null ? "" : text.strip();
        if (input.length() > MAX_SPEAK_CHARS) {
            input = input.substring(0, MAX_SPEAK_CHARS);
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("input", input);
            if (!ttsModel.isBlank()) {
                payload.put("model", ttsModel);
            }
            if (!ttsVoice.isBlank()) {
                payload.put("voice", ttsVoice);
            }
            payload.put("response_format", "wav");
            payload.put("speed", ttsSpeed);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ttsBaseUrl + "/v1/audio/speech"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)));
            authorize(request, ttsApiKey);
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new VoiceException("Speech synthesis failed (HTTP " + response.statusCode() + "): "
                        + abbreviate(new String(response.body(), StandardCharsets.UTF_8)));
            }
            return response.body();
        } catch (IOException exception) {
            throw new VoiceException("Speech synthesis server unreachable at " + ttsBaseUrl + ": " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new VoiceException("Interrupted", exception);
        }
    }

    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) throws IOException {
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void authorize(HttpRequest.Builder request, String apiKey) {
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
    }

    private static String trimSlash(String url) {
        String value = url == null ? "" : url.strip();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.endsWith("/v1")) {
            value = value.substring(0, value.length() - 3);
        }
        return value;
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }

    /** Voice failure with a user-readable message. */
    public static class VoiceException extends RuntimeException {
        public VoiceException(String message) {
            super(message);
        }

        public VoiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
