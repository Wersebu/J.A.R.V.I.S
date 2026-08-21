package com.jarvis.common.image;

/**
 * Why the deterministic resolver selected the historical images it selected for one request -
 * reported to diagnostics (never re-derived from the model's own text).
 */
public enum ImageSelectionReason {

    /** No images involved in this request at all. */
    NONE,
    /** Only the current message's own images were sent; nothing in the text referenced history. */
    CURRENT_ONLY,
    /** The text named a specific historical image (label, ordinal, or file name). */
    HISTORICAL_IMAGE_REFERENCE,
    /** The text referred to "the/an earlier image" without pinning down exactly one. */
    GENERAL_HISTORICAL_REFERENCE
}
