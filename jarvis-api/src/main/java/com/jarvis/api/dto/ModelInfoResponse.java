package com.jarvis.api.dto;

/**
 * One installed model, as shown to UI clients.
 *
 * @param name model identifier
 * @param family model family, when known
 * @param parameterSize human-readable parameter size, when known
 * @param sizeBytes on-disk size in bytes
 */
public record ModelInfoResponse(String name, String family, String parameterSize, long sizeBytes) {
}
