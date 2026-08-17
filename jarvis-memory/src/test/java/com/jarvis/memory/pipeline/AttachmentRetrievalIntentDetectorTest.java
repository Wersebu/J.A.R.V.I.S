package com.jarvis.memory.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the detector that flags a TOOL_REQUEST that redundantly asks a tool to
 * fetch/analyze an image already attached to the current message, instead of the model reading
 * it directly. Matching is action-word + attachment-noun based (not fixed phrases), so it must
 * generalize across languages and phrasing without being tied to any one workflow.
 */
class AttachmentRetrievalIntentDetectorTest {

    private final AttachmentRetrievalIntentDetector detector = new AttachmentRetrievalIntentDetector();

    @Test
    void flagsThePolishStoreAuditExampleFromTheBugReport() {
        boolean result = detector.looksLikeCurrentAttachmentRetrieval(
                "Pobierz i przeanalizuj zalaczony obraz zawierajacy liste adresow sklepow.", "");

        assertThat(result).isTrue();
    }

    @Test
    void flagsEnglishAttachmentRetrievalGoals() {
        assertThat(detector.looksLikeCurrentAttachmentRetrieval("Retrieve the attached screenshot.", "")).isTrue();
        assertThat(detector.looksLikeCurrentAttachmentRetrieval("Load the uploaded image and analyze it.", "")).isTrue();
        assertThat(detector.looksLikeCurrentAttachmentRetrieval("Find the attached photo.", "")).isTrue();
    }

    @Test
    void doesNotFlagAGenuineKnowledgeWorkspaceLookupWithNoAttachmentWords() {
        boolean result = detector.looksLikeCurrentAttachmentRetrieval(
                "Sprawdz w zapisanej wiedzy jaka mam karte graficzna.", "");

        assertThat(result).isFalse();
    }

    @Test
    void doesNotFlagARequestToSaveOrCreateFromAnAttachment() {
        // "save"/"create" are deliberately excluded from the action-word set so a genuine request
        // to persist an attachment into the Knowledge Workspace is never blocked.
        boolean result = detector.looksLikeCurrentAttachmentRetrieval(
                "Zapisz zalaczone zdjecie jako nowy dokument w Knowledge Workspace.", "");

        assertThat(result).isFalse();
    }

    @Test
    void doesNotFlagAGeocodingGoalThatAlreadyContainsExtractedAddressesInsteadOfAskingForTheImage() {
        boolean result = detector.looksLikeCurrentAttachmentRetrieval(
                "Geocode the following extracted store addresses: Korczaka 7, 08-400 Garwolin; Targowa 1, 08-400 Garwolin.",
                "Need coordinates to build the visit schedule.");

        assertThat(result).isFalse();
    }

    @Test
    void handlesNullGoalAndReasonSafely() {
        assertThat(detector.looksLikeCurrentAttachmentRetrieval(null, null)).isFalse();
    }
}
