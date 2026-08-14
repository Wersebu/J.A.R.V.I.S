package com.jarvis.api.dto;

import java.util.Set;

/**
 * One installed model, as shown to UI clients.
 *
 * @param name model identifier
 * @param family model family, when known
 * @param parameterSize human-readable parameter size, when known
 * @param sizeBytes on-disk size in bytes
 * @param capabilities provider-reported capabilities, e.g. {@code ["TEXT","VISION","TOOLS"]}
 */
public record ModelInfoResponse(String name, String family, String parameterSize, long sizeBytes, Set<String> capabilities) {
}
