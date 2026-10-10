package com.jarvis.knowledge.vault;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Detects documents that look like they contain credentials so they are never chunked, embedded
 * or returned to the model. Detection is deliberately conservative: a false positive only keeps a
 * note out of the index (it stays visible and editable in the vault), a false negative could leak
 * a secret into model context.
 */
public final class SecretScanner {

    private static final List<NamedPattern> PATTERNS = List.of(
            new NamedPattern("private-key", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")),
            new NamedPattern("aws-access-key", Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b")),
            new NamedPattern("github-token", Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b")),
            new NamedPattern("openai-style-key", Pattern.compile("\\bsk-[A-Za-z0-9_-]{24,}\\b")),
            new NamedPattern("slack-token", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}\\b")),
            new NamedPattern("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\b")),
            new NamedPattern("assigned-secret", Pattern.compile(
                    "(?im)^\\s*[\"']?[A-Za-z0-9_.-]*(api[_-]?key|secret|password|passwd|haslo|hasło|token|client[_-]?secret)[A-Za-z0-9_.-]*[\"']?"
                            // placeholders such as ${ENV_VAR}, <your-key> or {{key}} are not secrets
                            + "\\s*[:=]\\s*[\"']?(?![$<{%])[^\\s\"'#]{12,}"))
    );

    private SecretScanner() {
    }

    /**
     * Returns the kind of secret found, if any.
     *
     * @param content document content
     * @return detected secret kind
     */
    public static Optional<String> detect(String content) {
        if (content == null || content.isBlank()) {
            return Optional.empty();
        }
        for (NamedPattern pattern : PATTERNS) {
            if (pattern.pattern().matcher(content).find()) {
                return Optional.of(pattern.name());
            }
        }
        return Optional.empty();
    }

    private record NamedPattern(String name, Pattern pattern) {
    }
}
