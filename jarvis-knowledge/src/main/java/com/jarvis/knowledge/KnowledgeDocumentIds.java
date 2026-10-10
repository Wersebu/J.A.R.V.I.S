package com.jarvis.knowledge;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic document UUIDs for the metadata index.
 *
 * <p>Previously every restart assigned random UUIDs, so version history stored under
 * {@code .history/<uuid>} was detached from its document after a restart. The UUID is now
 * derived from the frontmatter {@code id} when a note declares one (stable across renames and
 * moves), or from the relative path otherwise (stable across restarts).
 */
public final class KnowledgeDocumentIds {

    private static final Pattern ID_LINE = Pattern.compile("^id\\s*:\\s*[\"']?([^\"'#]+?)[\"']?\\s*(#.*)?$");

    private KnowledgeDocumentIds() {
    }

    /**
     * Returns the UUID for a document.
     *
     * @param relativePath path relative to the knowledge root
     * @param frontmatterId frontmatter id, or {@code ""}
     * @return deterministic UUID
     */
    public static UUID of(String relativePath, String frontmatterId) {
        if (frontmatterId != null && !frontmatterId.isBlank()) {
            try {
                return UUID.fromString(frontmatterId.strip());
            } catch (IllegalArgumentException ignored) {
                return UUID.nameUUIDFromBytes(("id:" + frontmatterId.strip()).getBytes(StandardCharsets.UTF_8));
            }
        }
        return UUID.nameUUIDFromBytes(("path:" + relativePath.replace('\\', '/')).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads the frontmatter {@code id} of a Markdown file without parsing the whole document.
     *
     * @param file file
     * @return id, or {@code ""}
     */
    public static String frontmatterId(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".md") && !name.endsWith(".markdown")) {
            return "";
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            if (first == null || !first.replace("﻿", "").strip().equals("---")) {
                return "";
            }
            String line;
            int read = 0;
            while ((line = reader.readLine()) != null && read++ < 100) {
                String stripped = line.strip();
                if (stripped.equals("---") || stripped.equals("...")) {
                    return "";
                }
                Matcher matcher = ID_LINE.matcher(stripped);
                if (matcher.matches()) {
                    return matcher.group(1).strip();
                }
            }
            return "";
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }
}
