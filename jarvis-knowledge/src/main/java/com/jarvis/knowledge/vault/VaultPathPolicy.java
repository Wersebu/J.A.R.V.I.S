package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.KnowledgeException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Single place deciding which vault paths are reachable and which are indexed.
 *
 * <p>All path input coming from the model, the REST API or the Windows client goes through
 * {@link #resolve(String)}: it rejects absolute paths, drive letters, {@code ..} segments, NUL
 * bytes, and symlinks whose real target lies outside the vault root.
 */
public final class VaultPathPolicy {

    /** Built-in exclusions: Obsidian/VCS internals, Jarvis internals, temp/backup files and likely secrets. */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            "**/.obsidian/**", "**/.git/**", "**/.trash/**", "**/.history/**", "**/.drafts/**", "**/.jarvis/**",
            "**/.stfolder/**", "**/.stversions/**", "**/.sync/**", "**/node_modules/**",
            "**/.DS_Store", "**/Thumbs.db", "**/desktop.ini", "**/.gitkeep", "**/.gitignore",
            "**/*.tmp", "**/*.temp", "**/*.swp", "**/*.swo", "**/*~", "**/~$*", "**/.~lock.*", "**/.#*",
            "**/*.bak", "**/*.backup", "**/*.orig", "**/*.old", "**/*.sync-conflict-*", "**/*.crdownload", "**/*.part",
            "**/.env", "**/.env.*", "**/*.env", "**/*.key", "**/*.pem", "**/*.p12", "**/*.pfx", "**/*.kdbx",
            "**/id_rsa*", "**/id_ed25519*", "**/*secret*", "**/*credential*", "**/*password*", "**/*.token"
    );

    private static final Set<String> TEXT_EXTENSIONS = Set.of("md", "markdown", "txt");

    private final Path root;
    private final GlobMatcher excludes;
    private final PromptFileGuard promptGuard;

    /**
     * Creates the policy.
     *
     * @param root vault root
     * @param excludeDefaults whether {@link #DEFAULT_EXCLUDES} apply
     * @param extraExcludes additional globs
     * @param promptGuard system prompt guard
     */
    public VaultPathPolicy(Path root, boolean excludeDefaults, List<String> extraExcludes, PromptFileGuard promptGuard) {
        this.root = root.toAbsolutePath().normalize();
        List<String> globs = new ArrayList<>();
        if (excludeDefaults) {
            globs.addAll(DEFAULT_EXCLUDES);
        }
        globs.addAll(extraExcludes == null ? List.of() : extraExcludes);
        this.excludes = new GlobMatcher(globs);
        this.promptGuard = promptGuard;
    }

    /**
     * Returns the absolute normalized vault root.
     *
     * @return root
     */
    public Path root() {
        return root;
    }

    /**
     * Returns the system prompt guard.
     *
     * @return prompt guard
     */
    public PromptFileGuard promptGuard() {
        return promptGuard;
    }

    /**
     * Resolves a vault-relative path safely.
     *
     * @param relativePath untrusted relative path ({@code /} or {@code \} separators)
     * @return absolute path inside the vault
     * @throws VaultPathException when the path is malformed or escapes the vault
     */
    public Path resolve(String relativePath) {
        String cleaned = clean(relativePath);
        Path resolved = cleaned.isEmpty() ? root : root.resolve(cleaned).normalize();
        if (!resolved.startsWith(root)) {
            throw new VaultPathException("Path escapes the vault: " + relativePath);
        }
        ensureNoSymlinkEscape(resolved, relativePath);
        return resolved;
    }

    /**
     * Normalizes an untrusted relative path into {@code a/b/c} form.
     *
     * @param relativePath untrusted path
     * @return cleaned relative path ({@code ""} for the root)
     */
    public static String clean(String relativePath) {
        String value = relativePath == null ? "" : relativePath.strip().replace('\\', '/');
        if (value.indexOf('\0') >= 0) {
            throw new VaultPathException("Path contains a NUL byte");
        }
        if (value.startsWith("/") || value.startsWith("~") || value.matches("^[A-Za-z]:.*") || value.startsWith("//")) {
            throw new VaultPathException("Absolute paths are not allowed: " + relativePath);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : value.split("/+")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                throw new VaultPathException("Parent directory segments are not allowed: " + relativePath);
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    /**
     * Converts an absolute path inside the vault to a relative {@code /} path.
     *
     * @param path absolute path
     * @return relative path ({@code ""} for the root)
     */
    public String relativize(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.equals(root)) {
            return "";
        }
        if (!normalized.startsWith(root)) {
            throw new VaultPathException("Path escapes the vault: " + path);
        }
        return root.relativize(normalized).toString().replace('\\', '/');
    }

    /**
     * Returns whether a path is excluded from the vault views and the index.
     *
     * @param relativePath relative path
     * @return true for internals, temp/backup files, secrets and system prompt files
     */
    public boolean isExcluded(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        if (excludes.matches(relativePath)) {
            return true;
        }
        return promptGuard != null && promptGuard.isPromptByLocation(root.resolve(relativePath), relativePath);
    }

    /**
     * Returns whether an absolute path is excluded (paths outside the vault are always excluded).
     *
     * @param path absolute path
     * @return true when excluded
     */
    public boolean isExcluded(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            return true;
        }
        return !normalized.equals(root) && isExcluded(relativize(normalized));
    }

    /**
     * Returns whether a file is a text note that is chunk-indexed.
     *
     * @param relativePath relative path
     * @return true for Markdown / text notes
     */
    public boolean isNote(String relativePath) {
        return TEXT_EXTENSIONS.contains(extension(relativePath));
    }

    /**
     * Returns whether a regular file inside the vault may be read: it must not be a symlink to a
     * file outside the vault.
     *
     * @param file absolute file path
     * @return true when the real file lies inside the vault
     */
    public boolean isSafeRegularFile(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
            return false;
        }
        try {
            Path real = file.toRealPath();
            return Files.isRegularFile(real) && real.startsWith(realRoot());
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Returns the lowercase extension of a path.
     *
     * @param relativePath path
     * @return extension without dot
     */
    public static String extension(String relativePath) {
        String name = relativePath == null ? "" : relativePath;
        int slash = name.lastIndexOf('/');
        name = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = name.lastIndexOf('.');
        return dot <= 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void ensureNoSymlinkEscape(Path resolved, String original) {
        Path existing = resolved;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.startsWith(root)) {
            return;
        }
        try {
            Path real = existing.toRealPath();
            if (!real.startsWith(realRoot())) {
                throw new VaultPathException("Path escapes the vault through a symbolic link: " + original);
            }
        } catch (IOException exception) {
            throw new VaultPathException("Path could not be verified: " + original);
        }
    }

    private Path realRoot() {
        try {
            return Files.exists(root) ? root.toRealPath() : root;
        } catch (IOException exception) {
            return root;
        }
    }

    /**
     * Rejected vault path.
     */
    public static final class VaultPathException extends KnowledgeException {
        /**
         * Creates the exception.
         *
         * @param message reason
         */
        public VaultPathException(String message) {
            super(message);
        }
    }
}
