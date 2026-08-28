package com.jarvis.core.moderation;

import com.jarvis.api.dto.moderation.ModerationRequest;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic signal detector. It can only raise risk, never clear model findings.
 */
@Component
public class ModerationPromptInjectionDetector {

    private static final List<Pattern> PROMPT_INJECTION_PATTERNS = List.of(
            Pattern.compile("\\bignore\\s+(all\\s+)?(previous|above)\\s+instructions\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bzignoruj\\s+(poprzednie|wczesniejsze|wszystkie)\\s+instrukcje\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bSYSTEM\\s*:", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\breturn\\s+exactly\\s*\\{", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bzwroc\\s+dokladnie\\s*\\{", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\botworz\\s+(ten\\s+)?link\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bopen\\s+(this\\s+)?link\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\buse\\s+(a\\s+)?tool\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\buzyj\\s+narzedzia\\b", Pattern.CASE_INSENSITIVE)
    );

    /**
     * Detects prompt-injection-like text in inert TopkiMC payload fields.
     *
     * @param request moderation request
     * @return true when deterministic signal was found
     */
    public boolean detectsPromptInjection(ModerationRequest request) {
        String combined = (safe(request.title()) + "\n" + safe(request.plainText())).toLowerCase(Locale.ROOT);
        if (PROMPT_INJECTION_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(combined).find())) {
            return true;
        }
        return request.externalUrls().stream().anyMatch(this::suspiciousInstructionUrl)
                || request.imageUrls().stream().anyMatch(this::suspiciousInstructionUrl);
    }

    private boolean suspiciousInstructionUrl(String url) {
        String normalized = safe(url).toLowerCase(Locale.ROOT);
        if (normalized.contains("ignore") || normalized.contains("system") || normalized.contains("prompt")) {
            return true;
        }
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return host != null && !host.equals(IDN.toASCII(host));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
