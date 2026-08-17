package com.jarvis.memory.pipeline;

import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Detects when a {@code TOOL_REQUEST} goal/reason is actually just an attempt to retrieve or
 * analyze an image/file that is already attached to the current user message - a request no tool
 * can ever fulfill, since current-message attachments are transient input, not persisted
 * Knowledge Workspace documents.
 *
 * <p>Matches on a (action word) AND (attachment noun) combination rather than fixed phrases, so
 * it generalizes across languages and phrasing instead of being tied to one workflow's wording.
 * Deliberately excludes words like "save"/"create" so a genuine request to persist an attachment
 * into the Knowledge Workspace is never flagged.</p>
 */
@Service
public class AttachmentRetrievalIntentDetector {

    private static final Set<String> ATTACHMENT_NOUNS = Set.of(
            "image", "images", "photo", "photos", "picture", "pictures", "screenshot", "screenshots",
            "attachment", "attachments", "attached file", "uploaded file",
            "obraz", "obrazy", "obrazek", "obrazka", "obrazie", "zdjecie", "zdjecia", "zdjeciu", "zdjeciem",
            "zalacznik", "zalaczniki", "zalacznika", "zalaczony", "zalaczone", "zalaczonego", "zalaczonym",
            "przeslany", "przeslane", "przeslanego", "przeslanym", "screen"
    );

    private static final Set<String> RETRIEVAL_ACTION_WORDS = Set.of(
            "retrieve", "fetch", "load", "obtain", "get", "find", "locate", "access", "download",
            "analyze", "analyse", "read", "review", "check", "process", "open",
            "pobierz", "wczytaj", "znajdz", "sciagnij", "przeanalizuj", "sprawdz", "odczytaj",
            "obejrzyj", "otworz", "pobrac", "wyszukaj"
    );

    /**
     * Returns true when the given goal/reason text looks like a request to fetch or analyze an
     * image/attachment, rather than a request to perform some other external operation.
     *
     * @param goal tool request goal
     * @param reason tool request reason
     * @return true when this looks like a redundant current-message attachment retrieval attempt
     */
    public boolean looksLikeCurrentAttachmentRetrieval(String goal, String reason) {
        String normalized = normalize((goal == null ? "" : goal) + " " + (reason == null ? "" : reason));
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized, ATTACHMENT_NOUNS) && containsAny(normalized, RETRIEVAL_ACTION_WORDS);
    }

    private String normalize(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    private boolean containsAny(String value, Set<String> needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
