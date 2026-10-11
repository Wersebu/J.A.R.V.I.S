package com.jarvis.core.files;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Files Jarvis hands to a user next to an answer (e.g. the PDF of a mail attachment it just read), so they can check
 * the original. Kept only in memory, only for a few hours and only for the user they were made for: nothing is
 * written to the server's disk, and an id alone is useless to anyone else.
 */
@Component
public class SharedFiles {

    /** One shared file. */
    public record SharedFile(String id, String ownerUserId, String filename, String mimeType, byte[] content, Instant expiresAt) {
    }

    static final Duration TIME_TO_LIVE = Duration.ofHours(12);
    static final long MAX_TOTAL_BYTES = 256L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final LinkedHashMap<String, SharedFile> files = new LinkedHashMap<>();
    private long totalBytes;

    public SharedFiles() {
        this(Clock.systemUTC());
    }

    SharedFiles(Clock clock) {
        this.clock = clock;
    }

    /**
     * Shares a file with one user.
     *
     * @return the file with its new unguessable id
     */
    public synchronized SharedFile share(String ownerUserId, String filename, String mimeType, byte[] content) {
        if (ownerUserId == null || ownerUserId.isBlank()) {
            throw new IllegalArgumentException("A shared file needs its owner.");
        }
        evictExpired();
        // Oldest files go first when memory is full; a newer answer matters more than one from hours ago.
        Iterator<SharedFile> oldest = files.values().iterator();
        while (totalBytes + content.length > MAX_TOTAL_BYTES && oldest.hasNext()) {
            totalBytes -= oldest.next().content().length;
            oldest.remove();
        }
        byte[] idBytes = new byte[24];
        RANDOM.nextBytes(idBytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(idBytes);
        SharedFile file = new SharedFile(id, ownerUserId, safeName(filename), mimeType == null || mimeType.isBlank()
                ? "application/octet-stream" : mimeType, content.clone(), clock.instant().plus(TIME_TO_LIVE));
        files.put(id, file);
        totalBytes += content.length;
        return file;
    }

    /** The file, only for its owner and only before it expires. */
    public synchronized Optional<SharedFile> find(String id, String userId) {
        evictExpired();
        SharedFile file = files.get(id);
        return file != null && file.ownerUserId().equals(userId) ? Optional.of(file) : Optional.empty();
    }

    private void evictExpired() {
        Instant now = clock.instant();
        for (Iterator<Map.Entry<String, SharedFile>> it = files.entrySet().iterator(); it.hasNext(); ) {
            SharedFile file = it.next().getValue();
            if (!now.isBefore(file.expiresAt())) {
                totalBytes -= file.content().length;
                it.remove();
            }
        }
    }

    /** A plain file name the user's computer can save safely: no folders, no control or reserved characters. */
    static String safeName(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}<>:\"|?*]", "_").strip();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        if (name.length() > 150) {
            int dot = name.lastIndexOf('.');
            String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
            name = name.substring(0, 150 - extension.length()) + extension;
        }
        return name.isBlank() ? "plik" : name;
    }
}
