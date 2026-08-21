package com.jarvis.common.image;

import java.util.List;

/**
 * Structured account of every image known to a conversation at the point one request is being
 * handled, and which of them were actually selected to be re-sent as native vision input this turn.
 * Built once (by the deterministic selection resolver) and threaded through the pipeline/tool loop
 * so the model, the prompt, and diagnostics all agree on the same picture.
 *
 * @param currentMessageImages images uploaded with the current message
 * @param availableHistoricalImages images from earlier messages in this conversation still
 *         {@link ConversationImageStatus#AVAILABLE}
 * @param expiredHistoricalImages images from earlier messages no longer available (expired,
 *         missing, or deleted)
 * @param selectedImagesForModel the final, ordered, deduplicated set actually passed as native
 *         vision input this turn (always includes every current-message image plus whichever
 *         historical images the resolver selected)
 * @param skippedDueToLimit historical images that matched a reference but were left out because
 *         {@code max-active-images}/{@code max-total-bytes} was reached
 * @param selectionReason why the resolver selected what it selected
 */
public record ConversationImageContext(
        List<ConversationImageRecord> currentMessageImages,
        List<ConversationImageRecord> availableHistoricalImages,
        List<ConversationImageRecord> expiredHistoricalImages,
        List<ConversationImageRecord> selectedImagesForModel,
        List<ConversationImageRecord> skippedDueToLimit,
        ImageSelectionReason selectionReason
) {

    /**
     * Normalizes null fields.
     */
    public ConversationImageContext {
        currentMessageImages = currentMessageImages == null ? List.of() : List.copyOf(currentMessageImages);
        availableHistoricalImages = availableHistoricalImages == null ? List.of() : List.copyOf(availableHistoricalImages);
        expiredHistoricalImages = expiredHistoricalImages == null ? List.of() : List.copyOf(expiredHistoricalImages);
        selectedImagesForModel = selectedImagesForModel == null ? List.of() : List.copyOf(selectedImagesForModel);
        skippedDueToLimit = skippedDueToLimit == null ? List.of() : List.copyOf(skippedDueToLimit);
        selectionReason = selectionReason == null ? ImageSelectionReason.NONE : selectionReason;
    }

    /**
     * The empty context - no images involved at all, the overwhelmingly common case for a plain
     * text-only message.
     *
     * @return an empty context
     */
    public static ConversationImageContext empty() {
        return new ConversationImageContext(List.of(), List.of(), List.of(), List.of(), List.of(), ImageSelectionReason.NONE);
    }

    /**
     * Whether there is anything at all worth telling the model/UI about (current images, some
     * available/expired history, or a non-trivial selection).
     *
     * @return true when this context carries any real information
     */
    public boolean hasAnyImages() {
        return !currentMessageImages.isEmpty() || !availableHistoricalImages.isEmpty() || !expiredHistoricalImages.isEmpty();
    }

    /**
     * Every image known for this request, current and historical (available or not) combined - for
     * diagnostics/UI reporting that needs to describe the whole picture, not just the selection.
     *
     * @return current, available-historical, and expired-historical images concatenated in that order
     */
    public List<ConversationImageRecord> allKnownImages() {
        List<ConversationImageRecord> all = new java.util.ArrayList<>(currentMessageImages.size()
                + availableHistoricalImages.size() + expiredHistoricalImages.size());
        all.addAll(currentMessageImages);
        all.addAll(availableHistoricalImages);
        all.addAll(expiredHistoricalImages);
        return List.copyOf(all);
    }
}
