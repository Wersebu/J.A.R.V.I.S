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

    // Broader than "photo/image" wording on purpose - a user very commonly refers back to what they
    // uploaded as "the attachment" or "the file", not "the image", especially in Polish. The exact
    // regression this set fixes: "co wyslalem ci wczesniej w zalaczniku?" ("what did I send you
    // earlier in the attachment?") previously matched none of IMAGE_NOUNS at all, so no historical
    // reference was ever detected and the model got only metadata, never the pixels.
    private static final Set<String> ATTACHMENT_NOUNS = Set.of(
            "zalacznik", "zalaczniki", "zalaczniku", "zalacznika", "zalacznikiem", "zalacznikow", "zalacznikami",
            "plik", "pliku", "pliki", "plikow", "plikiem", "plikami",
            "attachment", "attachments", "file", "files", "attached"
    );

    // "co wyslalem wczesniej?" / "what did I send earlier?" has no image/attachment noun at all -
    // only the verb itself implies "the thing I sent (as an attachment)". Treated as a reference
    // cue on its own, same as an image/attachment noun.
    private static final Set<String> SEND_VERBS = Set.of(
            "wyslalem", "wyslales", "wyslal", "wyslala", "wysylalem", "wysylales",
            "przeslalem", "przeslales", "przeslal", "przeslala", "przesylalem", "przesylales",
            "sent", "uploaded"
    );

    // Minimum token length before fuzzy (edit-distance) matching kicks in - short words have too
    // many accidental one-edit neighbors to fuzzy-match safely.
    private static final int FUZZY_MIN_LENGTH = 5;

    private static final Map<String, Integer> ORDINAL_WORDS = Map.ofEntries(
            Map.entry("pierwsze", 1), Map.entry("pierwszy", 1), Map.entry("pierwsza", 1), Map.entry("pierwszej", 1),
            Map.entry("drugie", 2), Map.entry("drugi", 2), Map.entry("druga", 2), Map.entry("drugiej", 2),
            Map.entry("trzecie", 3), Map.entry("trzeci", 3), Map.entry("trzecia", 3), Map.entry("trzeciej", 3),
            Map.entry("czwarte", 4), Map.entry("czwarty", 4), Map.entry("czwarta", 4), Map.entry("czwartej", 4),
            Map.entry("piate", 5), Map.entry("piaty", 5), Map.entry("piata", 5), Map.entry("piatej", 5)
    );

    private static final Set<String> LAST_WORDS = Set.of(
            "ostatnie", "ostatni", "ostatnia", "ostatniej", "ostatnim", "last",
            "poprzedni", "poprzednia", "poprzednie", "poprzedniej", "poprzednim", "poprzedniego", "poprzednich", "previous",
            "wczesniejszy", "wczesniejsza", "wczesniejsze", "wczesniejszej", "wczesniejszym", "wczesniejszego", "earlier");

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

        if (!properties.enabled() || (availableHistorical.isEmpty() && expiredHistorical.isEmpty())) {
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

        // A reference is "present" from a matched noun/verb cue, OR from an ordinal reference that
        // was attempted but failed to resolve (e.g. "drugie zdjecie" when there is no second image) -
        // either way the user clearly asked about a historical image and Core must not silently
        // treat the message as if nothing had been asked for.
        boolean referenceCuePresent = ordinalReferenceAttempted
                || containsAnyReferenceCue(tokens);

        if (!explicitMatches.isEmpty()) {
            reason = ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE;
            selected.addAll(explicitMatches);
        } else if (referenceCuePresent && availableHistorical.isEmpty()) {
            // A reference was made, but nothing is currently available (everything expired/missing) -
            // not ambiguous, just unmet. ModelExecutionStage's deterministic gate tells this apart
            // from "nothing was ever uploaded" by checking expiredHistoricalImages() itself.
            reason = ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE;
        } else if (referenceCuePresent && availableHistorical.size() == 1) {
            // Only one candidate exists at all - unambiguous by elimination, matching the required
            // "co wyslalem ci wczesniej w zalaczniku?" regression scenario exactly.
            reason = ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE;
            selected.add(availableHistorical.get(0));
        } else if (referenceCuePresent && properties.autoAttachMode() == ConversationImageProperties.AutoAttachMode.REFERENCED_ONLY) {
            // Several candidates exist and this mode never guesses on a vague reference - the user
            // must be asked to name one before any model call happens.
            reason = ImageSelectionReason.AMBIGUOUS_REFERENCE;
        } else if (referenceCuePresent) {
            // Several candidates - take every image from the single most recent message that has
            // any, bounded by the configured limits below.
            int lastOrdinal = availableHistorical.stream().mapToInt(ConversationImageRecord::sourceMessageOrdinal).max().orElse(0);
            List<ConversationImageRecord> lastMessageImages = availableHistorical.stream()
                    .filter(record -> record.sourceMessageOrdinal() == lastOrdinal)
                    .sorted(Comparator.comparingInt(ConversationImageRecord::ordinalInMessage))
                    .toList();
            reason = ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE;
            selected.addAll(lastMessageImages);
        } else {
            reason = currentMessageImages.isEmpty() ? ImageSelectionReason.NONE : ImageSelectionReason.CURRENT_ONLY;
        }

        List<ConversationImageRecord> withinLimits = enforceLimits(selected, currentMessageImages.size(), properties, skipped);
        boolean historicalSelected = withinLimits.size() > currentMessageImages.size();

        // The configured limit ate every candidate from the last-message selection above - treat
        // this as "Core could not safely resolve exactly which one" (per the safe-fallback
        // requirement) and retry with just the single most recent available image, which is far
        // more likely to fit than a whole message's worth of images.
        if (reason == ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE && !historicalSelected && !availableHistorical.isEmpty()) {
            ConversationImageRecord mostRecent = availableHistorical.get(availableHistorical.size() - 1);
            List<ConversationImageRecord> retrySelected = new ArrayList<>(currentMessageImages);
            retrySelected.add(mostRecent);
            List<ConversationImageRecord> retrySkipped = new ArrayList<>();
            List<ConversationImageRecord> retryWithinLimits = enforceLimits(retrySelected, currentMessageImages.size(), properties, retrySkipped);
            if (retryWithinLimits.size() > currentMessageImages.size()) {
                withinLimits = retryWithinLimits;
                skipped = retrySkipped;
                historicalSelected = true;
            }
        }

        // A reference was detected and historical candidates exist, but nothing could safely be
        // selected (limit exhausted even after the single-image retry, or REFERENCED_ONLY forbade
        // guessing) - the caller must ask the user to name the image instead of silently proceeding
        // as if no reference had been made, or letting the model guess.
        if ((reason == ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE || reason == ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE)
                && !historicalSelected && !availableHistorical.isEmpty()) {
            reason = ImageSelectionReason.AMBIGUOUS_REFERENCE;
        }
        // An ordinal/noun/verb reference was made but there is no available historical image at all
        // (everything expired/missing, or nothing was ever uploaded) - not ambiguous, just unmet;
        // ModelExecutionStage's deterministic gate distinguishes this from AMBIGUOUS by checking
        // expiredHistoricalImages() itself, so leave the reason as GENERAL_HISTORICAL_REFERENCE here
        // rather than overloading AMBIGUOUS_REFERENCE for a different real-world situation.

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

    /**
     * True when the message contains any word suggesting the user is referring back to something
     * they uploaded earlier - an image/screenshot noun, an attachment/file noun, or a "send" verb
     * with no object ("co wyslalem wczesniej?"). Matches exactly (after diacritic/case
     * normalization) or within one edit (typo tolerance) for words long enough for that to be safe.
     *
     * @param tokens the normalized, tokenized message text
     * @return true when a reference cue is present anywhere in the message
     */
    private boolean containsAnyReferenceCue(List<String> tokens) {
        for (String token : tokens) {
            if (isReferenceWord(token, IMAGE_NOUNS) || isReferenceWord(token, ATTACHMENT_NOUNS)
                    || isReferenceWord(token, SEND_VERBS)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNounToken(String token) {
        return isReferenceWord(token, IMAGE_NOUNS) || isReferenceWord(token, ATTACHMENT_NOUNS);
    }

    /**
     * Matches {@code token} against {@code vocabulary} exactly, or - for tokens at least {@link
     * #FUZZY_MIN_LENGTH} characters long - within a single character edit (insertion, deletion, or
     * substitution), so a common typo ("zdjecie" -> "zdejcie") still matches. Never fuzzy-matches
     * short words, where a one-edit neighborhood is large enough to risk false positives.
     *
     * @param token candidate token
     * @param vocabulary the word set to match against
     * @return true when {@code token} matches a word in {@code vocabulary}
     */
    private boolean isReferenceWord(String token, Set<String> vocabulary) {
        if (vocabulary.contains(token)) {
            return true;
        }
        if (token.length() < FUZZY_MIN_LENGTH) {
            return false;
        }
        for (String word : vocabulary) {
            if (Math.abs(word.length() - token.length()) <= 1 && levenshteinDistanceAtMostOne(token, word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Cheap edit-distance-at-most-one check (no full DP table needed) - true when {@code a} can be
     * turned into {@code b} with a single insertion, deletion, or substitution.
     */
    private boolean levenshteinDistanceAtMostOne(String a, String b) {
        int lengthA = a.length();
        int lengthB = b.length();
        if (Math.abs(lengthA - lengthB) > 1) {
            return false;
        }
        int i = 0;
        int j = 0;
        boolean editUsed = false;
        while (i < lengthA && j < lengthB) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (editUsed) {
                return false;
            }
            editUsed = true;
            if (lengthA == lengthB) {
                i++;
                j++;
            } else if (lengthA > lengthB) {
                i++;
            } else {
                j++;
            }
        }
        return true;
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
            if (consumed.contains(index) || !isNounToken(tokens.get(index))) {
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
