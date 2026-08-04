package com.jarvis.memory.cognitive;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Deterministic memory classifier used before future LLM-based classification exists.
 */
@Component
public class DeterministicMemoryClassifier {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final Pattern OWNERSHIP = Pattern.compile("\\b(?:mam|posiadam|i own|i have)\\s+(.{2,80})", FLAGS);
    private static final Pattern PROJECT = Pattern.compile("\\b(?:pracuje nad|pracuję nad|tworze|tworzę|rozwijam|i develop|i am developing|i work on)\\s+(.{2,80})", FLAGS);
    private static final Pattern ATTRIBUTE = Pattern.compile("\\b(?:moj|mój|moja|moje|my)\\s+([\\p{L}0-9 _-]{2,40})\\s+(?:to|is)\\s+(.{2,80})", FLAGS);
    private static final Pattern PROCEDURE = Pattern.compile("\\b(?:procedura|workflow|instrukcja|steps to|how to)\\s+(.{2,120})", FLAGS);
    private static final Pattern EVENT = Pattern.compile("\\b(?:kupilem|kupiłem|finished|completed|started|utworzylem|utworzyłem|zaczalem|zacząłem)\\s+(.{2,120})", FLAGS);

    /**
     * Extracts memory candidates from a user message.
     *
     * @param message user message
     * @return memory candidates
     */
    public List<MemoryCandidate> classify(String message) {
        String source = message == null ? "" : message.strip();
        List<MemoryCandidate> candidates = new ArrayList<>();
        match(source, OWNERSHIP).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.SEMANTIC, "user", "owns", cleanup(value), 0.82)));
        match(source, PROJECT).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.SEMANTIC, "user", "develops", cleanup(value), 0.82)));
        Matcher attributeMatcher = ATTRIBUTE.matcher(source);
        while (attributeMatcher.find()) {
            candidates.add(new MemoryCandidate(
                    MemoryCandidateType.SEMANTIC,
                    "user",
                    "has." + cleanup(attributeMatcher.group(1)).replace(" ", "_"),
                    cleanup(attributeMatcher.group(2)),
                    0.78
            ));
        }
        match(source, PROCEDURE).forEach(value ->
                candidates.add(new MemoryCandidate(MemoryCandidateType.PROCEDURAL, "procedure", cleanup(value), cleanup(source), 0.7)));
        match(source, EVENT).forEach(value ->
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

    private String cleanup(String value) {
        return value == null ? "" : value.replaceAll("[.?!]+$", "").strip();
    }
}
