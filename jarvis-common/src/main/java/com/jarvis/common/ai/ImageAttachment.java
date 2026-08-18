package com.jarvis.common.ai;

/**
 * One image attached to a chat request, ready to send natively to a vision-capable model.
 *
 * @param base64Data raw image bytes, base64-encoded (no data-URI prefix, matching Ollama's format)
 * @param originalFileName original user-visible file name, for logs and diagnostics
 * @param attachmentId real current-message attachment id, so tools with provenance requirements
 *         (e.g. {@code storeDataset.CREATE_DATASET}'s {@code sourceAttachmentId}) can be told this
 *         id instead of having to invent one - blank when not resolved from a real attachment
 */
public record ImageAttachment(String base64Data, String originalFileName, String attachmentId) {

    /**
     * Creates an image attachment with no known attachment id.
     *
     * @param base64Data raw image bytes, base64-encoded
     * @param originalFileName original user-visible file name
     */
    public ImageAttachment(String base64Data, String originalFileName) {
        this(base64Data, originalFileName, "");
    }
}
