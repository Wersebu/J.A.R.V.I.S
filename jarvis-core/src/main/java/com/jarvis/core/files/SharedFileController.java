package com.jarvis.core.files;

import com.jarvis.common.auth.CurrentUserContext;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Downloads a file Jarvis shared with the logged-in user ({@link SharedFiles}). Another user's id answers 404,
 * exactly like an expired one, so ids reveal nothing.
 */
@RestController
public class SharedFileController {

    private final SharedFiles files;

    public SharedFileController(SharedFiles files) {
        this.files = files;
    }

    @GetMapping("/api/v1/files/{id}")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        String user;
        try {
            user = CurrentUserContext.requireAuthenticatedUserId();
        } catch (IllegalStateException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return files.find(id, user)
                .map(file -> {
                    MediaType type;
                    try {
                        type = MediaType.parseMediaType(file.mimeType());
                    } catch (RuntimeException exception) {
                        type = MediaType.APPLICATION_OCTET_STREAM;
                    }
                    return ResponseEntity.ok()
                            .contentType(type)
                            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                                    .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                            .header(HttpHeaders.CACHE_CONTROL, "no-store")
                            .header("X-Content-Type-Options", "nosniff")
                            .body(file.content());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }
}
