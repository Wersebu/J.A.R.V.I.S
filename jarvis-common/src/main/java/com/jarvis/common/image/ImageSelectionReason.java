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
    /** The text named a specific historical image (label, ordinal, or file name), or there was only
     *  one available historical image so the reference was unambiguous by elimination. */
    HISTORICAL_IMAGE_REFERENCE,
    /** The text referred to "the/an earlier image" without pinning down exactly one; the images
     *  from the most recent message that has any were selected instead. */
    GENERAL_HISTORICAL_REFERENCE,
    /** A historical image was referenced but Core could not safely resolve exactly which one -
     *  either several equally plausible candidates exist and the configured limit does not allow
     *  sending all of them, or the auto-attach mode forbids guessing on a vague reference. The user
     *  must be asked to name the image before any model call happens. */
    AMBIGUOUS_REFERENCE
}
