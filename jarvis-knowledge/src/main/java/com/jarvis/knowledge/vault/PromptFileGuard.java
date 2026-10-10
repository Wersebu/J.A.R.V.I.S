package com.jarvis.knowledge.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps the system prompt (config/jarvis.md) and copies of it out of the knowledge index.
 *
 * <p>The prompt is an instruction contract, not knowledge: if a copy were indexed, a search hit
 * could re-inject (possibly stale) instructions as "retrieved data". A file is treated as a prompt
 * copy when it is one of the configured prompt files, is named like one ({@code jarvis.md},
 * {@code jarvis (1).md}, {@code jarvis-backup.md}), declares {@code type: system-prompt} in its
 * frontmatter, has identical normalized content, or shares most of its substantive lines with
 * the prompt.
 */
public final class PromptFileGuard {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptFileGuard.class);
    /** Copy-like names of the prompt file anywhere in the vault: "jarvis (1).md", "jarvis-backup.md", "jarvis kopia.md". */
    private static final Pattern PROMPT_COPY_NAME = Pattern.compile(
            "(?i)^jarvis(\\s*\\(\\d+\\)|[ ._-]*(copy|kopia|backup|old|bak|orig|prompt|system|\\d+))+\\.(md|markdown|txt)$");
    /** The prompt file name itself; excluded only at the vault root or in a config/prompt folder - a note about the Jarvis project elsewhere is knowledge. */
    private static final Pattern PROMPT_FILE_NAME = Pattern.compile("(?i)^jarvis\\.(md|markdown|txt)$");
    private static final Pattern PROMPT_FOLDER = Pattern.compile("(?i)^(config|prompt|prompts|system|system-prompt)$");
    private static final Pattern SYSTEM_PROMPT_TYPE = Pattern.compile(
            "(?im)^(type|jarvis-role|role)\\s*:\\s*[\"']?(system[-_ ]?prompt|prompt[-_ ]?systemowy)[\"']?\\s*$");
    private static final double COPY_LINE_OVERLAP = 0.6d;
    private static final int MIN_SIGNIFICANT_LINE = 24;

    private final List<Path> promptPaths;
    private final Set<String> promptHashes = new HashSet<>();
    private final List<Set<String>> promptLineSets = new ArrayList<>();

    /**
     * Loads prompt fingerprints.
     *
     * @param promptFiles prompt file paths (missing files are ignored)
     */
    public PromptFileGuard(List<Path> promptFiles) {
        List<Path> resolved = new ArrayList<>();
        for (Path promptFile : promptFiles) {
            Path absolute = promptFile.toAbsolutePath().normalize();
            resolved.add(absolute);
            if (!Files.isRegularFile(absolute)) {
                continue;
            }
            try {
                String content = Files.readString(absolute, StandardCharsets.UTF_8);
                promptHashes.add(hash(normalize(content)));
                promptLineSets.add(significantLines(content));
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("[VAULT] Prompt file could not be fingerprinted path={} error={}", absolute, exception.getMessage());
            }
        }
        this.promptPaths = List.copyOf(resolved);
    }

    /**
     * Returns whether the file name alone marks a prompt or prompt copy.
     *
     * @param file file path
     * @return true for prompt-like names or configured prompt files
     */
    public boolean isPromptByLocation(Path file) {
        return isPromptByLocation(file, null);
    }

    /**
     * Returns whether the location marks a prompt or prompt copy.
     *
     * @param file file path
     * @param relativePath vault-relative path, or {@code null} when unknown
     * @return true for configured prompt files, copy-like prompt names, or {@code jarvis.md} at the root / in a config folder
     */
    public boolean isPromptByLocation(Path file, String relativePath) {
        Path absolute = file.toAbsolutePath().normalize();
        for (Path promptPath : promptPaths) {
            if (absolute.equals(promptPath) || sameRealFile(absolute, promptPath)) {
                return true;
            }
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        if (PROMPT_COPY_NAME.matcher(name).matches()) {
            return true;
        }
        if (!PROMPT_FILE_NAME.matcher(name).matches()) {
            return false;
        }
        if (relativePath == null) {
            return true;
        }
        String[] segments = relativePath.replace('\\', '/').split("/");
        return segments.length == 1 || PROMPT_FOLDER.matcher(segments[segments.length - 2]).matches();
    }

    /**
     * Returns whether content is a copy of the prompt.
     *
     * @param content candidate content
     * @return true for system prompt copies
     */
    public boolean isPromptContent(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String head = content.length() > 2000 ? content.substring(0, 2000) : content;
        if (head.startsWith("---") && SYSTEM_PROMPT_TYPE.matcher(head).find()) {
            return true;
        }
        if (promptHashes.isEmpty()) {
            return false;
        }
        if (promptHashes.contains(hash(normalize(content)))) {
            return true;
        }
        Set<String> lines = significantLines(content);
        if (lines.size() < 5) {
            return false;
        }
        for (Set<String> promptLines : promptLineSets) {
            long shared = lines.stream().filter(promptLines::contains).count();
            if ((double) shared / lines.size() >= COPY_LINE_OVERLAP) {
                return true;
            }
        }
        return false;
    }

    private boolean sameRealFile(Path left, Path right) {
        try {
            return Files.exists(left) && Files.exists(right) && Files.isSameFile(left, right);
        } catch (IOException exception) {
            return false;
        }
    }

    private static Set<String> significantLines(String content) {
        Set<String> lines = new HashSet<>();
        for (String line : content.split("\\R")) {
            String normalized = line.strip().toLowerCase(Locale.ROOT);
            if (normalized.length() >= MIN_SIGNIFICANT_LINE && !normalized.chars().allMatch(ch -> ch == '=' || ch == '-' || ch == '#' || ch == ' ')) {
                lines.add(normalized);
            }
        }
        return lines;
    }

    private static String normalize(String content) {
        String value = content.startsWith("﻿") ? content.substring(1) : content;
        return value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
