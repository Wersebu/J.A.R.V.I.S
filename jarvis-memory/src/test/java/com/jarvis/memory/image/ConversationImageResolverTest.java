package com.jarvis.memory.image;

import com.jarvis.common.image.ConversationImageContext;
import com.jarvis.common.image.ConversationImageRecord;
import com.jarvis.common.image.ConversationImageStatus;
import com.jarvis.common.image.ImageSelectionReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the deterministic (never model-driven) historical-image reference resolver - the model
 * cannot be trusted to ask for an image it has not seen yet, so Core must decide what to re-attach
 * purely from the current message's own text.
 */
class ConversationImageResolverTest {

    private final ConversationImageResolver resolver = new ConversationImageResolver();

    @Test
    void messageWithNoImageReferenceNeverAttachesTheWholeArchive() {
        List<ConversationImageRecord> historical = List.of(image("a", 1, 1), image("b", 1, 2));

        ConversationImageContext context = resolver.resolve("jaka jest stolica Francji?", List.of(), historical, defaultProperties());

        assertThat(context.selectedImagesForModel()).isEmpty();
        assertThat(context.selectionReason()).isEqualTo(ImageSelectionReason.NONE);
    }

    @Test
    void secondImageReferenceSelectsTheRightHistoricalImage() {
        List<ConversationImageRecord> historical = List.of(image("a", 1, 1), image("b", 1, 2));

        ConversationImageContext context = resolver.resolve(
                "sprawdz jeszcze raz drugie zdjecie i porownaj je z tym co ustalilismy",
                List.of(), historical, defaultProperties());

        assertThat(context.selectedImagesForModel()).extracting(ConversationImageRecord::attachmentId).containsExactly("b");
        assertThat(context.selectionReason()).isEqualTo(ImageSelectionReason.HISTORICAL_IMAGE_REFERENCE);
    }

    @Test
    void fileNameReferenceSelectsTheRightHistoricalImage() {
        ConversationImageRecord village = new ConversationImageRecord("id-a", "c1", "m1", 1, 1, "image-1",
                "a", "w1", "village-map.png", "png", 1000, Instant.now(), Instant.now().plusSeconds(3600),
                ConversationImageStatus.AVAILABLE);
        ConversationImageRecord other = image("b", 1, 2);

        ConversationImageContext context = resolver.resolve(
                "wroc do village-map i przeanalizuj prawy dolny rog", List.of(), List.of(village, other), defaultProperties());

        assertThat(context.selectedImagesForModel()).extracting(ConversationImageRecord::attachmentId).containsExactly("a");
    }

    @Test
    void currentAndHistoricalImagesAreCombinedWithoutDuplicates() {
        ConversationImageRecord current = image("current", 2, 1);
        List<ConversationImageRecord> historical = List.of(image("a", 1, 1), image("b", 1, 2));

        ConversationImageContext context = resolver.resolve(
                "sprawdz pierwsze zdjecie", List.of(current), historical, defaultProperties());

        assertThat(context.selectedImagesForModel()).extracting(ConversationImageRecord::attachmentId)
                .containsExactly("current", "a");
    }

    @Test
    void generalReferenceIncludesAvailableHistoricalImagesUpToTheLimit() {
        List<ConversationImageRecord> historical = List.of(image("a", 1, 1), image("b", 2, 1), image("c", 3, 1));

        ConversationImageContext context = resolver.resolve(
                "wroc do tamtego obrazu z wczesniej", List.of(), historical, defaultProperties());

        assertThat(context.selectionReason()).isEqualTo(ImageSelectionReason.GENERAL_HISTORICAL_REFERENCE);
        assertThat(context.selectedImagesForModel()).hasSize(3);
    }

    @Test
    void referencedOnlyModeNeverAttachesOnAVagueReference() {
        List<ConversationImageRecord> historical = List.of(image("a", 1, 1));
        ConversationImageProperties properties = new ConversationImageProperties(true, Duration.ofMinutes(60), 8, 16_000_000L,
                ConversationImageProperties.AutoAttachMode.REFERENCED_ONLY);

        ConversationImageContext context = resolver.resolve("a co z tamtym zdjeciem?", List.of(), historical, properties);

        assertThat(context.selectedImagesForModel()).isEmpty();
    }

    @Test
    void maxActiveImagesLimitTrimsHistoricalSelectionAndReportsSkipped() {
        List<ConversationImageRecord> historical = List.of(image("a", 1, 1), image("b", 2, 1), image("c", 3, 1));
        ConversationImageProperties properties = new ConversationImageProperties(true, Duration.ofMinutes(60), 2, 16_000_000L,
                ConversationImageProperties.AutoAttachMode.REFERENCED_OR_RECENT);

        ConversationImageContext context = resolver.resolve("pokaz mi te zdjecia z wczesniej", List.of(), historical, properties);

        assertThat(context.selectedImagesForModel()).hasSize(2);
        assertThat(context.skippedDueToLimit()).hasSize(1);
    }

    @Test
    void maxTotalBytesLimitTrimsHistoricalSelectionAndReportsSkipped() {
        ConversationImageRecord big1 = imageWithSize("a", 1, 1, 10_000_000L);
        ConversationImageRecord big2 = imageWithSize("b", 2, 1, 10_000_000L);
        ConversationImageProperties properties = new ConversationImageProperties(true, Duration.ofMinutes(60), 8, 15_000_000L,
                ConversationImageProperties.AutoAttachMode.REFERENCED_OR_RECENT);

        ConversationImageContext context = resolver.resolve("pokaz zdjecia sprzed chwili", List.of(),
                List.of(big1, big2), properties);

        assertThat(context.selectedImagesForModel()).hasSize(1);
        assertThat(context.skippedDueToLimit()).hasSize(1);
    }

    // The complex multi-set scenario from the task: message 1 has images A/B, message 3 has C,
    // message 5 asks to compare "the second image from the first message" with "the last image".
    @Test
    void compoundReferenceAcrossMultipleMessagesResolvesTheExactRightImages() {
        ConversationImageRecord a = image("a", 1, 1);
        ConversationImageRecord b = image("b", 1, 2);
        ConversationImageRecord c = image("c", 2, 1);

        ConversationImageContext context = resolver.resolve(
                "porownaj drugie zdjecie z pierwszej wiadomosci z ostatnim zdjeciem",
                List.of(), List.of(a, b, c), defaultProperties());

        assertThat(context.selectedImagesForModel()).extracting(ConversationImageRecord::attachmentId)
                .containsExactlyInAnyOrder("b", "c");
    }

    private ConversationImageProperties defaultProperties() {
        return new ConversationImageProperties(true, Duration.ofMinutes(60), 8, 16_777_216L,
                ConversationImageProperties.AutoAttachMode.REFERENCED_OR_RECENT);
    }

    private ConversationImageRecord image(String attachmentId, int sourceMessageOrdinal, int ordinalInMessage) {
        return imageWithSize(attachmentId, sourceMessageOrdinal, ordinalInMessage, 1000L);
    }

    private ConversationImageRecord imageWithSize(String attachmentId, int sourceMessageOrdinal, int ordinalInMessage, long sizeBytes) {
        Instant now = Instant.now();
        return new ConversationImageRecord("id-" + attachmentId, "conversation-1", "m" + sourceMessageOrdinal,
                sourceMessageOrdinal, ordinalInMessage, "image-" + attachmentId, attachmentId, "workspace-1",
                attachmentId + ".png", "png", sizeBytes, now, now.plusSeconds(3600), ConversationImageStatus.AVAILABLE);
    }
}
