package com.jarvis.common.ai;

/**
 * Describes an arbitrary image using a dedicated, independently-named vision model - never the
 * currently active chat model. This is a deliberately narrow seam, separate from {@link
 * AIProvider}: an active chat model without vision capability normally has an attached image
 * rejected outright rather than routed anywhere, but a tool (e.g. a browser/game screenshot
 * inspector) can use this to ask a targeted question about an image and get a text answer back,
 * without ever needing the active model itself to see.
 */
public interface VisionDescriptionProvider {

    /**
     * Describes an image by answering a specific question about it.
     *
     * @param model vision-capable model name, independent of the active chat model
     * @param question free-form question about the image (e.g. "describe precisely the middle
     *                 section of the screen"), not a fixed generic caption prompt
     * @param base64Image the image, base64-encoded, no {@code data:} URI prefix
     * @param forceCpu when true, the implementation should avoid evicting other loaded models
     *                 from GPU memory to serve this call (e.g. by forcing CPU-only inference),
     *                 even at the cost of a much slower answer
     * @return the vision model's text answer
     */
    String describeImage(String model, String question, String base64Image, boolean forceCpu);
}
