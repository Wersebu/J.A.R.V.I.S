package com.jarvis.api.dto;

import java.time.Instant;

/**
 * Response containing editable system instructions.
 *
 * @param instructions system instructions
 * @param updatedAt response timestamp
 */
public record SystemPromptResponse(String instructions, Instant updatedAt) {
}
