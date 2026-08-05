package com.jarvis.memory.grounding;

import com.jarvis.common.prompt.PersonalQueryAnalysis;
import com.jarvis.common.prompt.PersonalQueryDetector;
import com.jarvis.common.prompt.PersonalTopic;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects personal user questions with lightweight Polish and English token scoring.
 */
@Service
public class HeuristicPersonalQueryDetector implements PersonalQueryDetector {

    private static final Set<String> PERSONAL_MARKERS = Set.of(
            "my", "mine", "me", "i", "own", "have", "use", "using", "remember",
            "moj", "moja", "moje", "mam", "posiadam", "uzywam", "pamietasz", "mnie", "mi", "moim"
    );

    private static final Map<PersonalTopic, Set<String>> TOPIC_TERMS = Map.of(
            PersonalTopic.DEVICE, Set.of(
                    "gpu", "rtx", "nvidia", "radeon", "cpu", "processor", "graphics", "card", "hardware",
                    "device", "pc", "computer", "setup", "ram", "motherboard", "storage", "psu",
                    "karta", "graficzna", "grafika", "procesor", "komputer", "sprzet", "urzadzenie",
                    "dysk", "plyta", "zasilacz", "pamiec", "podzespol", "ollama"
            ),
            PersonalTopic.VEHICLE, Set.of(
                    "car", "vehicle", "drive", "auto", "samochod", "audi", "pojazd", "jezdze"
            ),
            PersonalTopic.PROJECT, Set.of(
                    "project", "repo", "application", "working", "build", "projekt", "aplikacja", "pracuje",
                    "buduje", "jarvis", "nova"
            ),
            PersonalTopic.WORK, Set.of(
                    "work", "job", "company", "role", "praca", "firma", "stanowisko", "zawod"
            ),
            PersonalTopic.PREFERENCE, Set.of(
                    "like", "prefer", "favorite", "favourite", "ide", "editor", "lubie", "wole", "ulubiony",
                    "preferuje"
            ),
            PersonalTopic.IDENTITY, Set.of(
                    "name", "identity", "who", "wiek", "imie", "nazywam", "kim", "tozsamosc"
            )
    );

    @Override
    public PersonalQueryAnalysis analyze(String message) {
        Set<String> tokens = tokenize(message);
        if (tokens.isEmpty()) {
            return PersonalQueryAnalysis.none();
        }
        boolean hasPersonalMarker = tokens.stream().anyMatch(PERSONAL_MARKERS::contains);
        Map<PersonalTopic, Integer> scores = new HashMap<>();
        TOPIC_TERMS.forEach((topic, terms) -> {
            int score = (int) tokens.stream().filter(terms::contains).count();
            if (score > 0) {
                scores.put(topic, score);
            }
        });
        PersonalTopic topic = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(PersonalTopic.OTHER);
        int topicScore = scores.getOrDefault(topic, 0);
        boolean personalQuery = hasPersonalMarker && (topicScore > 0 || asksAboutRecall(tokens));
        if (!personalQuery) {
            return PersonalQueryAnalysis.none();
        }
        double confidence = Math.min(0.95, 0.45 + (hasPersonalMarker ? 0.25 : 0.0) + (topicScore * 0.15));
        return new PersonalQueryAnalysis(true, topic, confidence);
    }

    private boolean asksAboutRecall(Set<String> tokens) {
        return tokens.contains("remember") || tokens.contains("pamietasz") || tokens.contains("wiesz");
    }

    private Set<String> tokenize(String message) {
        if (message == null || message.isBlank()) {
            return Set.of();
        }
        String normalized = Normalizer.normalize(message.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (normalized.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
