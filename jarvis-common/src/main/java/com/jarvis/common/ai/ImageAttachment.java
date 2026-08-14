package com.jarvis.common.ai;

/**
 * One image attached to a chat request, ready to send natively to a vision-capable model.
 *
 * @param base64Data raw image bytes, base64-encoded (no data-URI prefix, matching Ollama's format)
 * @param originalFileName original user-visible file name, for logs and diagnostics
 */
public record ImageAttachment(String base64Data, String originalFileName) {
}
