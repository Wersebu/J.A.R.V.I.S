package com.jarvis.knowledge.vault;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Case-insensitive glob matching on vault-relative paths using {@code /} separators.
 *
 * <p>{@code **}{@code /} matches zero or more directories (so {@code **}{@code /*.tmp} also matches a
 * top-level {@code a.tmp}, unlike {@link java.nio.file.PathMatcher}), {@code *} matches within one
 * path segment, and {@code dir/**} also matches {@code dir} itself.
 */
public final class GlobMatcher {

    private final List<Pattern> patterns;

    /**
     * Compiles globs.
     *
     * @param globs glob patterns
     */
    public GlobMatcher(List<String> globs) {
        this.patterns = globs.stream()
                .filter(glob -> glob != null && !glob.isBlank())
                .map(GlobMatcher::compile)
                .toList();
    }

    /**
     * Checks a relative path against every pattern.
     *
     * @param relativePath vault-relative path with {@code /} separators
     * @return true when any pattern matches the path or one of its parent directories
     */
    public boolean matches(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return false;
        }
        String path = relativePath.replace('\\', '/');
        for (Pattern pattern : patterns) {
            if (pattern.matcher(path).matches()) {
                return true;
            }
        }
        return false;
    }

    static Pattern compile(String glob) {
        String value = glob.trim().replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        StringBuilder regex = new StringBuilder();
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '*') {
                boolean doubleStar = index + 1 < value.length() && value.charAt(index + 1) == '*';
                if (doubleStar) {
                    boolean followedBySlash = index + 2 < value.length() && value.charAt(index + 2) == '/';
                    if (followedBySlash) {
                        regex.append("(?:.*/)?");
                        index += 3;
                    } else {
                        regex.append(".*");
                        index += 2;
                    }
                    continue;
                }
                regex.append("[^/]*");
            } else if (current == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(current)));
            }
            index++;
        }
        String compiled = regex.toString();
        // "dir/**" also matches the directory itself.
        if (value.endsWith("/**")) {
            String base = compiled.substring(0, compiled.length() - ".*".length());
            compiled = "(?:" + compiled + "|" + base.substring(0, base.length() - Pattern.quote("/").length()) + ")";
        }
        return Pattern.compile(compiled, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
