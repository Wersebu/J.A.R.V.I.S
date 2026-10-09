package com.jarvis.api.voice;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Voice mode endpoints for the Windows app: speech-to-text for the microphone and (optionally)
 * server text-to-speech for Jarvis's voice.
 */
@RestController
@RequestMapping("/api/v1/voice")
public class VoiceController {

    private final VoiceService voiceService;

    /**
     * Creates the controller.
     *
     * @param voiceService voice service
     */
    public VoiceController(VoiceService voiceService) {
        this.voiceService = voiceService;
    }

    /**
     * Returns which voice features the server provides.
     *
     * @return status
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return voiceService.status();
    }

    /**
     * Transcribes a recording sent as the raw request body (audio/wav).
     *
     * @param audio audio bytes
     * @param fileName optional file name (format hint)
     * @return {"text": "..."}
     */
    @PostMapping(value = "/transcribe", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(@RequestBody byte[] audio,
                                                          @RequestParam(value = "fileName", required = false) String fileName) {
        try {
            return ResponseEntity.ok(Map.of("text", voiceService.transcribe(audio, fileName)));
        } catch (VoiceService.VoiceException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", exception.getMessage()));
        }
    }

    /**
     * Speaks a text with the server voice.
     *
     * @param request {"text": "..."}
     * @return audio/wav
     */
    @PostMapping(value = "/speak", produces = {"audio/wav", MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<?> speak(@RequestBody Map<String, Object> request) {
        try {
            byte[] audio = voiceService.speak(String.valueOf(request.getOrDefault("text", "")));
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(audio);
        } catch (VoiceService.VoiceException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", exception.getMessage()));
        }
    }
}
