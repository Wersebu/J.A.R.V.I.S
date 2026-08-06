package com.jarvis.tools.knowledge.filing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default deterministic knowledge extractor.
 */
@Service
public class DefaultKnowledgeExtractionService implements KnowledgeExtractionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultKnowledgeExtractionService.class);
    private static final Pattern BIRTHDAY_PATTERN = Pattern.compile(
            "(?i)\\b(?:urodzinach|urodziny|birthday)\\b\\s+([\\p{L}0-9_-]{2,40})(?:\\s+(?:w|we|na|to|jest|ma))?\\s+(\\d{1,2}\\s+[\\p{L}]+)");
    private static final Pattern REVERSED_BIRTHDAY_PATTERN = Pattern.compile(
            "(?i)\\b([\\p{L}0-9_-]{2,40})\\b.*?\\b(?:urodziny|birthday)\\b.*?(\\d{1,2}\\s+[\\p{L}]+)");

    @Override
    public ExtractedKnowledge extract(String sourceMessage) {
        String source = sourceMessage == null ? "" : sourceMessage.trim();
        String cleaned = clean(source);
        ExtractedKnowledge extracted = birthday(source, cleaned);
        if (extracted == null) {
            extracted = hardware(source, cleaned);
        }
        if (extracted == null) {
            extracted = vehicle(source, cleaned);
        }
        if (extracted == null) {
            extracted = generic(source, cleaned);
        }
        LOGGER.info("""
                [KNOWLEDGE_EXTRACTION]
                sourceLength={}
                normalizedFact="{}"
                kind={}
                subject={}
                confidence={}
                """, source.length(), extracted.normalizedFact(), extracted.kind(), extracted.subject(), extracted.confidence());
        return extracted;
    }

    private ExtractedKnowledge birthday(String source, String cleaned) {
        Matcher matcher = BIRTHDAY_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            matcher = REVERSED_BIRTHDAY_PATTERN.matcher(cleaned);
            if (!matcher.find()) {
                return null;
            }
        }
        String subject = title(singularName(matcher.group(1)));
        String value = normalizeDate(matcher.group(2));
        if (isNoise(subject)) {
            subject = bestName(cleaned);
        }
        String fact = subject + " ma urodziny " + value + ".";
        return new ExtractedKnowledge(
                subject,
                "birthday",
                value,
                fact,
                KnowledgeKind.BIRTHDAY,
                List.of(subject),
                List.of("person", "birthday"),
                "pl",
                0.98d,
                !subject.isBlank() && !value.isBlank(),
                source
        );
    }

    private ExtractedKnowledge hardware(String source, String cleaned) {
        String normalized = normalize(cleaned);
        boolean personalPc = normalized.contains("moj pc") || normalized.contains("moj komputer")
                || normalized.contains("lokalny pc") || normalized.contains("local pc");
        boolean jarvisServer = normalized.contains("serwer") || normalized.contains("server");
        boolean hardware = personalPc || jarvisServer || normalized.contains("rtx") || normalized.contains("gtx")
                || normalized.contains("ram") || normalized.contains("gpu") || normalized.contains("cpu")
                || normalized.contains("i5-") || normalized.contains("i7-") || normalized.contains("ryzen");
        if (!hardware) {
            return null;
        }
        List<String> parts = splitHardwareFacts(cleaned);
        String subject = jarvisServer ? "JarvisServer" : "Damian PC";
        String fact = (jarvisServer ? "Serwer J.A.R.V.I.S." : "Komputer Damiana")
                + " ma konfiguracje: " + String.join(", ", parts) + ".";
        return new ExtractedKnowledge(
                subject,
                "hardware",
                String.join("; ", parts),
                fact,
                KnowledgeKind.HARDWARE,
                List.of(subject),
                List.of("hardware", "device"),
                "pl",
                0.90d,
                !parts.isEmpty(),
                source
        );
    }

    private ExtractedKnowledge vehicle(String source, String cleaned) {
        String normalized = normalize(cleaned);
        if (!normalized.contains("audi") && !normalized.contains("auto") && !normalized.contains("samochod")) {
            return null;
        }
        String subject = normalized.contains("a8") ? "Audi A8 D3" : "Vehicle";
        String fact = cleaned.endsWith(".") ? cleaned : cleaned + ".";
        return new ExtractedKnowledge(
                subject,
                "vehicle",
                cleaned,
                fact,
                KnowledgeKind.VEHICLE,
                List.of(subject),
                List.of("vehicle"),
                "pl",
                0.82d,
                !cleaned.isBlank(),
                source
        );
    }

    private ExtractedKnowledge generic(String source, String cleaned) {
        String subject = bestName(cleaned);
        boolean worthSaving = !cleaned.isBlank() && cleaned.length() > 5;
        String fact = cleaned.endsWith(".") ? cleaned : cleaned + ".";
        return new ExtractedKnowledge(
                subject,
                "fact",
                cleaned,
                fact,
                subject.isBlank() ? KnowledgeKind.OTHER : KnowledgeKind.PERSON_FACT,
                subject.isBlank() ? List.of() : List.of(subject),
                List.of("knowledge"),
                "pl",
                subject.isBlank() ? 0.45d : 0.70d,
                worthSaving,
                source
        );
    }

    private String clean(String source) {
        String value = stripDiacritics(source == null ? "" : source);
        value = value.replaceAll("[!?]+", " ");
        value = value.replaceAll("(?i)\\b(siemka|siema|hej|czesc|prosze|wiesz|ogolnie)\\b", " ");
        value = value.replaceAll("(?i)\\b(zapisz|zapamietaj|dodaj|utworz|stworz)\\b", " ");
        value = value.replaceAll("(?i)\\b(informacje|informacja|plik|dokument|jako|mi|dla mnie|chcialbym|zeby)\\b", " ");
        value = value.replaceAll("(?i)\\b(o|ze)\\b", " ");
        return value.replaceAll("\\s+", " ").trim();
    }

    private List<String> splitHardwareFacts(String cleaned) {
        String value = stripDiacritics(cleaned)
                .replaceAll("(?i)\\b(moj|ma|posiada|konfiguracje|komputer|pc|serwer|server)\\b", " ");
        String[] rawParts = value.split("\\s*(?:,|\\+| oraz | i )\\s*");
        List<String> parts = new ArrayList<>();
        for (String raw : rawParts) {
            String part = raw.trim();
            if (part.isBlank()) {
                continue;
            }
            parts.add(part.replaceAll("(?i)i5\\s*10\\s*600k|i510600k", "i5-10600K"));
        }
        return parts;
    }

    private String bestName(String value) {
        Matcher matcher = Pattern.compile("\\b([A-Z][\\p{L}]{2,40})\\b").matcher(value);
        while (matcher.find()) {
            String candidate = matcher.group(1);
            if (!isNoise(candidate)) {
                return title(singularName(candidate));
            }
        }
        Matcher lower = Pattern.compile("(?i)\\b([a-z]{4,40})\\b").matcher(stripDiacritics(value));
        while (lower.find()) {
            String candidate = lower.group(1);
            if (!isNoise(candidate)) {
                return title(singularName(candidate));
            }
        }
        return "";
    }

    private String singularName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > 4 && normalized.toLowerCase(Locale.ROOT).endsWith("ki")) {
            return normalized.substring(0, normalized.length() - 1) + "a";
        }
        if (normalized.length() > 4 && normalized.toLowerCase(Locale.ROOT).endsWith("ego")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    private boolean isNoise(String value) {
        String normalized = normalize(value);
        return normalized.isBlank()
                || List.of("urodziny", "urodzinach", "informacja", "informacje", "plik", "dokument", "moj").contains(normalized);
    }

    private String normalizeDate(String value) {
        return stripDiacritics(value == null ? "" : value)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String title(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
    }

    private String normalize(String value) {
        return stripDiacritics(value == null ? "" : value.toLowerCase(Locale.ROOT));
    }

    private String stripDiacritics(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l')
                .replace('Ł', 'L');
    }
}
