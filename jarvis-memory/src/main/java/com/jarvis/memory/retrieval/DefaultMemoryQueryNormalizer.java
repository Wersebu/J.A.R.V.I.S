package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryCategory;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight Polish and English memory query normalizer.
 */
@Component
public class DefaultMemoryQueryNormalizer implements MemoryQueryNormalizer {

    private static final Pattern LETTER_NUMBER_BOUNDARY = Pattern.compile("(?<=[a-zA-Z])(?=\\d)|(?<=\\d)(?=[a-zA-Z])");
    private static final Set<String> STOP_WORDS = Set.of(
            "jaka", "jaki", "jakie", "mam", "moj", "moja", "moje", "czy", "sie", "na",
            "czym", "co", "to", "jest", "the", "a", "an", "my", "do", "does", "what", "which", "i",
            "have", "has", "own", "owns", "use", "uses", "pamietasz"
    );
    private static final Map<String, Set<String>> CONCEPTS = concepts();

    @Override
    public MemoryQuery normalize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = normalizeText(query);
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            String stem = stem(token);
            if (!stem.isBlank() && !STOP_WORDS.contains(stem)) {
                tokens.add(stem);
                addConcepts(tokens, stem);
            }
        }
        return new MemoryQuery(query == null ? "" : query, List.copyOf(tokens), inferCategories(tokens));
    }

    private String normalizeText(String value) {
        String source = value == null ? "" : value.toLowerCase(Locale.ROOT);
        source = Normalizer.normalize(source, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        source = LETTER_NUMBER_BOUNDARY.matcher(source).replaceAll(" ");
        return source.replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    private String stem(String token) {
        String value = token.strip();
        for (String suffix : List.of("ami", "ego", "emu", "ach", "owa", "owe", "owy", "ych", "ing", "ed", "es", "ow", "a", "e", "y", "i")) {
            if (value.length() > suffix.length() + 3 && value.endsWith(suffix)) {
                return value.substring(0, value.length() - suffix.length());
            }
        }
        return value;
    }

    private void addConcepts(Set<String> tokens, String token) {
        Set<String> additions = CONCEPTS.get(token);
        if (additions != null) {
            additions.forEach(value -> {
                tokens.add(value);
                Set<String> nested = CONCEPTS.get(value);
                if (nested != null) {
                    tokens.addAll(nested);
                }
            });
        }
        if (token.matches("(rtx|gtx|rx|arc)?\\d{3,5}[a-z]*") || token.startsWith("rtx")) {
            tokens.addAll(Set.of("gpu", "graphics", "card", "nvidia", "hardware", "device", "rtx"));
        }
    }

    private Set<MemoryCategory> inferCategories(Set<String> tokens) {
        Set<MemoryCategory> categories = new LinkedHashSet<>();
        addCategory(categories, tokens, MemoryCategory.DEVICE, "gpu", "graphics", "hardware", "device", "computer", "pc", "rtx", "nvidia");
        addCategory(categories, tokens, MemoryCategory.VEHICLE, "car", "vehicle", "auto", "audi", "drive", "driv");
        addCategory(categories, tokens, MemoryCategory.PROJECT, "project", "projekt", "jarvis", "nova");
        addCategory(categories, tokens, MemoryCategory.PROGRAMMING, "java", "programming", "code", "ide", "intellij", "eclipse");
        addCategory(categories, tokens, MemoryCategory.PREFERENCE, "favorite", "favourite", "prefer", "like", "hobby");
        addCategory(categories, tokens, MemoryCategory.WORK, "work", "job", "company");
        addCategory(categories, tokens, MemoryCategory.PERSON, "person", "friend", "family");
        addCategory(categories, tokens, MemoryCategory.LOCATION, "location", "city", "country", "home");
        if (categories.isEmpty()) {
            categories.add(MemoryCategory.SEMANTIC);
        }
        return categories;
    }

    private void addCategory(Set<MemoryCategory> categories, Set<String> tokens, MemoryCategory category, String... markers) {
        for (String marker : markers) {
            if (tokens.contains(marker)) {
                categories.add(category);
                return;
            }
        }
    }

    private static Map<String, Set<String>> concepts() {
        Map<String, Set<String>> values = new LinkedHashMap<>();
        add(values, "kart", "card");
        add(values, "karta", "card");
        add(values, "graficzn", "graphics", "gpu", "hardware", "device");
        add(values, "grafik", "graphics", "gpu", "hardware", "device");
        add(values, "gpu", "graphics", "card", "hardware", "device", "rtx", "nvidia");
        add(values, "komputer", "computer", "pc", "hardware", "device");
        add(values, "ollam", "ollama", "gpu", "hardware", "device");
        add(values, "auto", "car", "vehicle");
        add(values, "samochod", "car", "vehicle");
        add(values, "drive", "car", "vehicle");
        add(values, "driv", "car", "vehicle");
        add(values, "projekt", "project");
        add(values, "jezyk", "language", "programming");
        add(values, "ide", "programming", "tool");
        add(values, "hobby", "preference");
        return Map.copyOf(values);
    }

    private static void add(Map<String, Set<String>> values, String key, String... synonyms) {
        values.put(key, Set.of(synonyms));
    }
}
