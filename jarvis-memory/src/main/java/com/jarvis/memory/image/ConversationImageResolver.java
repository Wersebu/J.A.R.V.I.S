package com.jarvis.memory.image;

import com.jarvis.common.image.ConversationImageContext;
import com.jarvis.common.image.ConversationImageRecord;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.common.image.ImageSelectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic (never model-driven) selection of which historical images from a conversation
 * should ride along with the current request as native vision input. The main model's first
 * decision happens before it can analyze anything, so it can never be trusted to ask for an image
 * it has not seen yet - Core decides this from the current message's own text alone, using plain
 * pattern matching, never an extra LLM call.
 */
@Service
public class ConversationImageResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationImageResolver.class);

    private static final int LAST_SENTINEL = Integer.MIN_VALUE;

    private static final Set<String> IMAGE_NOUNS = Set.of(
            "zdjecie", "zdjecia", "zdjeciu", "zdjeciem", "zdjeciach",
            "obraz", "obrazu", "obrazie", "obrazowi", "obrazek", "obrazka", "obrazku", "obrazem", "obrazki", "obrazy",
            "foto", "fotka", "fotke", "fotografia", "fotografie", "fotografii", "fotografiu",
            "screen", "screenie", "screenshot", "zrzut", "zrzutu", "zrzucie",
            "grafika", "grafike", "grafiki", "picture", "pictures", "image", "images", "photo", "photos"
    );

    private static final Map<String, Integer> ORDINAL_WORDS = Map.ofEntries(
            Map.entry("pierwsze", 1), Map.entry("pierwszy", 1), Map.entry("pierwsza", 1), Map.entry("pierwszej", 1),
            Map.entry("drugie", 2), Map.entry("drugi", 2), Map.entry("druga", 2), Map.entry("drugiej", 2),
            Map.entry("trzecie", 3), Map.entry("trzeci", 3), Map.entry("trzecia", 3), Map.entry("trzeciej", 3),
            Map.entry("czwarte", 4), Map.entry("czwarty", 4), Map.entry("czwarta", 4), Map.entry("czwartej", 4),
            Map.entry("piate", 5), Map.entry("piaty", 5), Map.entry("piata", 5), Map.entry("piatej", 5)
    );

    private static final Set<String> LAST_WORDS = Set.of(
            "ostatnie", "ostatni", "ostatnia", "ostatniej", "ostatnim", "last");

    private static final Set<String> MESSAGE_WORDS = Set.of(
            "wiadomosci", "wiadomosc", "wiadomosciach", "wiadomoscia", "message", "wiadomosc.");

    private static final Pattern LABEL_PATTERN = Pattern.compile("image-(\\d+)");

    /**
     * Resolves which images should be sent natively for one request.
     *
     * @param userMessage the current message's raw text
     * @param currentMessageImages images uploaded with the current message, in message order
     * @param historicalRecords every other known image for this conversation (any status)
     * @param properties conversation image configuration (limits, auto-attach mode)
     * @return the full structured context, including the final selection
     */
    public ConversationImageContext resolve(
            String userMessage,
            List<ConversationImageRecord> currentMessageImages,
            List<ConversationImageRecord> historicalRecords,
            ConversationImageProperties properties
    ) {
        List<ConversationImageRecord> availableHistorical = historicalRecords.stream()
                .filter(record -> record.status() == ConversationImageStatus.AVAILABLE)
                .sorted(Comparator.comparingInt(ConversationImageRecord::sourceMessageOrdinal)
                        .thenComparingInt(ConversationImageRecord::ordinalInMessage))
                .toList();
        List<ConversationImageRecord> expiredHistorical = historicalRecords.stream()
                .filter(record -> record.status() != ConversationImageStatus.AVAILABLE)
                .sorted(Comparator.comparingInt(ConversationImageRecord::sourceMessageOrdinal)
                        .thenComparingInt(ConversationImageRecord::ordinalInMessage))
                .toList();

        if (!properties.enabled() || availableHistorical.isEmpty()) {
            return new ConversationImageContext(currentMessageImages, availableHistorical, expiredHistorical,
                    currentMessageImages, List.of(), currentMessageImages.isEmpty()
                            ? ImageSelectionReason.NONE : ImageSelectionReason.CURRENT_ONLY);
        }

        List<ConversationImageRecord> allKnown = new ArrayList<>();
        allKnown.addAll(currentMessageImages);
        allKnown.addAll(availableHistorical);

        String normalized = normalize(userMessage);
        List<String> tokens = tokenize(normalized);
        Set<String> currentIds = currentMessageImages.stream().map(ConversationImageRecord::attachmentId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        LinkedHashSet<String> matchedIds = new LinkedHashSet<>();
        List<ConversationImageRecord> explicitMatches = new ArrayList<>();

        // 1. Explicit "image-N" conversational label.
        Matcher labelMatcher = LABEL_PATTERN.matcher(normalized);
        while (labelMatcher.find()) {
            String label = "image-" + labelMatcher.group(1);
            availableHistorical.stream()
                    .filter(record -> record.conversationLabel().equals(label))
                    .findFirst()
                    .ifPresent(record -> addMatch(record, matchedIds, explicitMatches));
        }

        // 2. Original file name mentioned in the text.
        for (ConversationImageRecord candidate : availableHistorical) {
            String fileName = normalize(candidate.originalFileName());
            String baseName = fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
            if (!baseName.isBlank() && baseName.length() >= 3 && normalized.contains(baseName)) {
                addMatch(candidate, matchedIds, explicitMatches);
            }
        }

        // 3. Ordinal reference(s) - "drugie zdjecie", optionally qualified by "... z pierwszej
        // wiadomosci", and possibly several in the same message (e.g. "porownaj X z Y").
        boolean ordinalReferenceAttempted = false;
        if (explicitMatches.isEmpty()) {
            for (Reference reference : findAllReferences(tokens)) {
                if (reference.imageOrdinal() == null) {
                    continue;
                }
                ordinalReferenceAttempted = true;
                ConversationImageRecord resolvedRecord = resolveOrdinalReference(
                        allKnown, reference.messageOrdinal(), reference.imageOrdinal());
                if (resolvedRecord != null && !currentIds.contains(resolvedRecord.attachmentId())) {
                    addMatch(resolvedRecord, matchedIds, explicitMatches);
                }
            }
        }

        List<ConversationImageRecord> selected = new ArrayList<>(currentMessageImages);
        List<ConversationImageRecord> skipped = new ArrayList<>();
        ImageSelectionReason reason;

        if (!explicitMatches.isEmpty()) {
            reason = ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE;
            selected.addAll(explicitMatches);
        } else if (containsAnyImageNoun(tokens) && !ordinalReferenceAttempted
                && properties.autoAttachMode() == ConversationImageProperties.AutoAttachMode.REFERENCED_OR_RECENT) {
            reason = ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE;
            List<ConversationImageRecord> mostRecentFirst = new ArrayList<>(availableHistorical);
            java.util.Collections.reverse(mostRecentFirst);
            selected.addAll(mostRecentFirst);
        } else {
            reason = currentMessageImages.isEmpty() ? ImageSelectionReason.NONE : ImageSelectionReason.CURRENT_ONLY;
        }

        List<ConversationImageRecord> withinLimits = enforceLimits(selected, currentMessageImages.size(), properties, skipped);

        LOGGER.info("[CONVERSATION_IMAGES] conversationId={} current={} historicalAvailable={} historicalExpired={} "
                        + "selected={} selectedBytes={} selectionReason={}",
                allKnown.isEmpty() ? "" : allKnown.get(0).conversationId(),
                currentMessageImages.size(), availableHistorical.size(), expiredHistorical.size(),
                withinLimits.size(), withinLimits.stream().mapToLong(ConversationImageRecord::sizeBytes).sum(), reason);

        return new ConversationImageContext(currentMessageImages, availableHistorical, expiredHistorical,
                withinLimits, skipped, reason);
    }

    private void addMatch(ConversationImageRecord record, Set<String> matchedIds, List<ConversationImageRecord> matches) {
        if (matchedIds.add(record.attachmentId())) {
            matches.add(record);
        }
    }

    /**
     * Applies {@code max-active-images}/{@code max-total-bytes} to the historical portion of the
     * selection only - current-message images are never dropped by this limit, matching the "always
     * include everything from the current message" requirement.
     */
    private List<ConversationImageRecord> enforceLimits(
            List<ConversationImageRecord> selected,
            int currentCount,
            ConversationImageProperties properties,
            List<ConversationImageRecord> skippedOut
    ) {
        List<ConversationImageRecord> result = new ArrayList<>(selected.subList(0, currentCount));
        long totalBytes = result.stream().mapToLong(ConversationImageRecord::sizeBytes).sum();
        for (int index = currentCount; index < selected.size(); index++) {
            ConversationImageRecord candidate = selected.get(index);
            boolean overCount = result.size() >= properties.maxActiveImages();
            boolean overBytes = totalBytes + candidate.sizeBytes() > properties.maxTotalBytes();
            if (overCount || overBytes) {
                skippedOut.add(candidate);
                continue;
            }
            result.add(candidate);
            totalBytes += candidate.sizeBytes();
        }
        return result;
    }

    /**
     * Resolves one already-parsed ordinal reference to a concrete image.
     *
     * @param allKnown every current+available-historical image, any order
     * @param messageOrdinal 1-based source-message ordinal, {@link #LAST_SENTINEL}, or {@code null}
     *         when the reference was not qualified by a specific message
     * @param imageOrdinal 1-based ordinal (within the message group, or globally when {@code
     *         messageOrdinal} is null), or {@link #LAST_SENTINEL}
     * @return the resolved record, or {@code null} when the ordinal is out of range
     */
    private ConversationImageRecord resolveOrdinalReference(
            List<ConversationImageRecord> allKnown, Integer messageOrdinal, int imageOrdinal
    ) {
        if (messageOrdinal != null) {
            int maxSourceOrdinal = allKnown.stream().mapToInt(ConversationImageRecord::sourceMessageOrdinal).max().orElse(1);
            int targetSourceOrdinal = messageOrdinal == LAST_SENTINEL ? maxSourceOrdinal : messageOrdinal;
            List<ConversationImageRecord> group = allKnown.stream()
                    .filter(record -> record.sourceMessageOrdinal() == targetSourceOrdinal)
                    .sorted(Comparator.comparingInt(ConversationImageRecord::ordinalInMessage))
                    .toList();
            int targetInMessage = imageOrdinal == LAST_SENTINEL ? group.size() : imageOrdinal;
            return targetInMessage >= 1 && targetInMessage <= group.size() ? group.get(targetInMessage - 1) : null;
        }
        List<ConversationImageRecord> ordered = allKnown.stream()
                .sorted(Comparator.comparingInt(ConversationImageRecord::sourceMessageOrdinal)
                        .thenComparingInt(ConversationImageRecord::ordinalInMessage))
                .toList();
        int targetGlobal = imageOrdinal == LAST_SENTINEL ? ordered.size() : imageOrdinal;
        return targetGlobal >= 1 && targetGlobal <= ordered.size() ? ordered.get(targetGlobal - 1) : null;
    }

    private boolean containsAnyImageNoun(List<String> tokens) {
        for (String token : tokens) {
            if (IMAGE_NOUNS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds every image-noun occurrence in the message and, for each, its own local ordinal (looked
     * up to 3 tokens back) and an optional message-ordinal qualifier (a {@code MESSAGE_WORDS} token
     * within the next 4 tokens, itself preceded by its own ordinal within 3 tokens) - so a single
     * message can name several distinct images at once (e.g. "compare X with Y"), each resolved
     * independently. Every token consumed by one reference is excluded from every later one, so the
     * same word is never reused across two different references.
     *
     * @param tokens the normalized, tokenized message text
     * @return every reference found, in reading order
     */
    private List<Reference> findAllReferences(List<String> tokens) {
        List<Reference> references = new ArrayList<>();
        Set<Integer> consumed = new java.util.HashSet<>();
        for (int index = 0; index < tokens.size(); index++) {
            if (consumed.contains(index) || !IMAGE_NOUNS.contains(tokens.get(index))) {
                continue;
            }
            Integer imageOrdinal = null;
            int imageOrdinalIndex = -1;
            for (int back = 1; back <= 3 && index - back >= 0; back++) {
                int candidateIndex = index - back;
                if (consumed.contains(candidateIndex)) {
                    continue;
                }
                String word = tokens.get(candidateIndex);
                if (LAST_WORDS.contains(word)) {
                    imageOrdinal = LAST_SENTINEL;
                    imageOrdinalIndex = candidateIndex;
                    break;
                }
                Integer value = ORDINAL_WORDS.get(word);
                if (value != null) {
                    imageOrdinal = value;
                    imageOrdinalIndex = candidateIndex;
                    break;
                }
            }
            Integer messageOrdinal = null;
            int messageNounIndex = -1;
            int messageOrdinalIndex = -1;
            for (int forward = 1; forward <= 4 && index + forward < tokens.size(); forward++) {
                int candidateIndex = index + forward;
                if (consumed.contains(candidateIndex) || !MESSAGE_WORDS.contains(tokens.get(candidateIndex))) {
                    continue;
                }
                messageNounIndex = candidateIndex;
                for (int back = 1; back <= 3 && candidateIndex - back > index; back++) {
                    int ordinalIndex = candidateIndex - back;
                    if (consumed.contains(ordinalIndex) || ordinalIndex == imageOrdinalIndex) {
                        continue;
                    }
                    String word = tokens.get(ordinalIndex);
                    if (LAST_WORDS.contains(word)) {
                        messageOrdinal = LAST_SENTINEL;
                        messageOrdinalIndex = ordinalIndex;
                        break;
                    }
                    Integer value = ORDINAL_WORDS.get(word);
                    if (value != null) {
                        messageOrdinal = value;
                        messageOrdinalIndex = ordinalIndex;
                        break;
                    }
                }
                break;
            }
            consumed.add(index);
            if (imageOrdinalIndex >= 0) {
                consumed.add(imageOrdinalIndex);
            }
            if (messageNounIndex >= 0) {
                consumed.add(messageNounIndex);
            }
            if (messageOrdinalIndex >= 0) {
                consumed.add(messageOrdinalIndex);
            }
            references.add(new Reference(imageOrdinal, messageOrdinal));
        }
        return references;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('ł', 'l').replace('Ł', 'L');
        return normalized.toLowerCase(Locale.ROOT);
    }

    private List<String> tokenize(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("[^a-z0-9-]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * One resolved image-noun occurrence.
     *
     * @param imageOrdinal 1-based ordinal attached to the noun, {@link #LAST_SENTINEL}, or {@code
     *         null} when the noun had no ordinal attached at all (a general reference)
     * @param messageOrdinal 1-based source-message ordinal the reference was qualified with, {@link
     *         #LAST_SENTINEL}, or {@code null} when unqualified
     */
    private record Reference(Integer imageOrdinal, Integer messageOrdinal) {
    }
}
