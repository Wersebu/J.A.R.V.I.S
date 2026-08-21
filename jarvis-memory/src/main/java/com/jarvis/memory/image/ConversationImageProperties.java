package com.jarvis.memory.image;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for conversation-scoped image memory - keeping images uploaded earlier in a
 * conversation available for later messages of the same conversation, bounded by a retention
 * window and per-request size/count limits. Sibling of {@link
 * com.jarvis.memory.conversation.ConversationHistoryProperties} under the same {@code
 * jarvis.conversation} root prefix.
 *
 * @param enabled master switch - when false, images are never re-attached from earlier messages
 *         (current-message vision is entirely unaffected either way)
 * @param retention how long a registered image stays {@code AVAILABLE}, counted from its original
 *         upload time - never extended by later reuse
 * @param maxActiveImages maximum number of images (current + re-attached historical, combined) sent
 *         natively in one request
 * @param maxTotalBytes maximum combined byte size of images (current + re-attached historical) sent
 *         natively in one request
 * @param autoAttachMode how aggressively to re-attach historical images when a message's text does
 *         not name one specific image
 */
@ConfigurationProperties(prefix = "jarvis.conversation.images")
public record ConversationImageProperties(
        boolean enabled,
        Duration retention,
        int maxActiveImages,
        long maxTotalBytes,
        AutoAttachMode autoAttachMode
) {

    /**
     * Applies safe defaults for missing/invalid values, so a malformed configuration degrades to a
     * safe, working default instead of failing startup or silently disabling the feature.
     */
    public ConversationImageProperties {
        retention = retention == null || retention.isNegative() || retention.isZero() ? Duration.ofMinutes(60) : retention;
        maxActiveImages = maxActiveImages <= 0 ? 8 : maxActiveImages;
        maxTotalBytes = maxTotalBytes <= 0 ? 16_777_216L : maxTotalBytes;
        autoAttachMode = autoAttachMode == null ? AutoAttachMode.REFERENCED_OR_RECENT : autoAttachMode;
    }

    /**
     * How historical (not current-message) images are selected when the current message's text
     * does not pin down exactly one specific image.
     */
    public enum AutoAttachMode {
        /** Only re-attach a historical image when the text names one specifically (label, ordinal, file name). */
        REFERENCED_ONLY,
        /** Also re-attach recent available historical images (bounded by the configured limits) on a general/vague reference. */
        REFERENCED_OR_RECENT
    }
}
