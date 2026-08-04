package com.jarvis.memory.cognitive;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic memory classifier used before future LLM-based classification exists.
 */
@Component
public class DeterministicMemoryClassifier {

    private static final Pattern OWNERSHIP = Pattern.compile("\\b(?:mam|posiadam|i own|i have)\\s+(.{2,80})");
    private static final Pattern PROJECT = Pattern.compile("\\b(?:pracuje nad|tworze|rozwijam|i develop|i am developing|i work on)\\s+(.{2,80})");
    private static final Pattern ATTRIBUTE = Pattern.compile("\\b(?:moj|moja|moje|my)\\s+([a-z0-9 _-]{2,40})\\s+(?:to|is)\\s+(.{2,80})");
    private static final Pattern PROCEDURE = Pattern.compile("\\b(?:procedura|workflow|instrukcja|steps to|how to)\\s+(.{2,120})");
    private static final Pattern EVENT = Pattern.compile("\\b(?:kupilem|kupiłem|finished|completed|started|utworzylem|utworzyłem|zaczalem|zacząłem)\\s+(.{2,120})");

    /**
     * Extracts memory candidates from a user message.
     *
     * @param message user message
     * @return memory candidates
     */
    public List<MemoryCandidate> classify(String message) {
        String source = message == null ? "" : message.strip();
        String normalized = normalize(source);
        List<MemoryCandidate> candidates = new ArrayList<>();
        match(normalized, OWNERSHIP).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.SEMANTIC, "user", "owns", cleanup(value), 0.82)));
        match(normalized, PROJECT).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.SEMANTIC, "user", "develops", cleanup(value), 0.82)));
        Matcher attributeMatcher = ATTRIBUTE.matcher(normalized);
        while (attributeMatcher.find()) {
            candidates.add(new MemoryCandidate(
                    MemoryCandidateType.SEMANTIC,
                    "user",
                    "has." + cleanup(attributeMatcher.group(1)).replace(" ", "_"),
                    cleanup(attributeMatcher.group(2)),
                    0.78
            ));
        }
        match(normalized, PROCEDURE).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.PROCEDURAL, "procedure", cleanup(value), cleanup(source), 0.7)));
        match(normalized, EVENT).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.EPISODIC, "event", cleanup(value), cleanup(source), 0.68)));
        return candidates;
    }

    private List<String> match(String source, Pattern pattern) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String cleanup(String value) {
        return value == null ? "" : value.replaceAll("[.?!]+$", "").strip();
    }
}
