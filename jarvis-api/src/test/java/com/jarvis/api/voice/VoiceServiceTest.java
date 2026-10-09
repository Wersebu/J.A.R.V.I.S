package com.jarvis.api.voice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceServiceTest {

    private HttpServer server;
    private final AtomicReference<String> transcriptionRequest = new AtomicReference<>("");
    private final AtomicReference<String> speechRequest = new AtomicReference<>("");

    @BeforeEach
    void startFakeAudioServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/audio/transcriptions", exchange -> {
            transcriptionRequest.set(exchange.getRequestHeaders().getFirst("Content-Type") + "\n"
                    + exchange.getRequestHeaders().getFirst("Authorization") + "\n"
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] body = "{\"text\":\" Otwórz Spotify i puść muzykę. \"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/v1/audio/speech", exchange -> {
            speechRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "RIFF....WAVE".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void transcribesThroughAnOpenAiCompatibleServer() {
        VoiceService service = service(url() + "/v1/", "");

        String text = service.transcribe("RIFFfakeWAV".getBytes(StandardCharsets.US_ASCII), "speech.wav");

        assertThat(text).isEqualTo("Otwórz Spotify i puść muzykę.");
        assertThat(transcriptionRequest.get())
                .startsWith("multipart/form-data; boundary=")
                .contains("Bearer secret")
                .contains("name=\"model\"\r\n\r\nSystran/faster-whisper-medium")
                .contains("name=\"language\"\r\n\r\npl")
                .contains("filename=\"speech.wav\"")
                .contains("RIFFfakeWAV");
        assertThat(service.status()).containsEntry("stt", true).containsEntry("tts", false);
    }

    @Test
    void speaksWithTheServerVoiceAsWav() {
        VoiceService service = service("", url());

        byte[] audio = service.speak("Gotowe.");

        assertThat(new String(audio, StandardCharsets.US_ASCII)).startsWith("RIFF");
        assertThat(speechRequest.get()).contains("\"input\":\"Gotowe.\"").contains("\"response_format\":\"wav\"")
                .contains("\"voice\":\"pl_gosia\"");
    }

    @Test
    void explainsWhatIsMissingWhenNotConfigured() {
        VoiceService service = service("", "");

        assertThatThrownBy(() -> service.transcribe(new byte[10], "a.wav")).hasMessageContaining("jarvis.voice.stt.base-url");
        assertThatThrownBy(() -> service.speak("x")).hasMessageContaining("system voice");
    }

    private VoiceService service(String sttUrl, String ttsUrl) {
        return new VoiceService(new ObjectMapper(), sttUrl, "secret", "Systran/faster-whisper-medium", "pl",
                ttsUrl, "", "piper", "pl_gosia", 1.0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
