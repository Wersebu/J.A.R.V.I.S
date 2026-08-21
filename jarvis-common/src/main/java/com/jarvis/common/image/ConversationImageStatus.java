package com.jarvis.common.image;

/**
 * Lifecycle status of one image registered against a conversation's {@link ConversationImageRecord}
 * registry entry. Distinct from a simple boolean "still there" flag because the reason an image is
 * no longer usable matters - the model and the UI both need to explain it honestly instead of
 * silently pretending the image never existed.
 */
public enum ConversationImageStatus {

    /** Registered, not expired, and its backing file was last confirmed present. */
    AVAILABLE,
    /** Its retention window elapsed - the file may or may not still physically exist. */
    EXPIRED,
    /** A live existence check found the backing file gone before its retention window elapsed. */
    MISSING,
    /** Removed because its conversation was deleted, or explicitly invalidated. */
    DELETED,
    /** Rejected at registration time (not a real image, unsafe path, unsupported type, ...). */
    INVALID
}
