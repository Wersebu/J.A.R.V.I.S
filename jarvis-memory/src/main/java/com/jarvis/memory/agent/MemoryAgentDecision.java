package com.jarvis.memory.agent;

import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryPriority;

import java.util.UUID;

/**
 * JSON-compatible decision returned by the AI memory agent.
 *
 * @param action memory action
 * @param memoryId existing memory identifier for update or delete
 * @param content normalized memory content
 * @param oldContent old memory content when updating
 * @param newContent new memory content when updating
 * @param priority memory priority
 * @param category memory category
 * @param confidence decision confidence
 * @param reason decision reason
 */
public record MemoryAgentDecision(
        MemoryAgentAction action,
        UUID memoryId,
        String content,
        String oldContent,
        String newContent,
        MemoryPriority priority,
        MemoryCategory category,
        double confidence,
        String reason
) {

    /**
     * Creates a NONE decision.
     *
     * @param reason reason
     * @return no-op decision
     */
    public static MemoryAgentDecision none(String reason) {
        return new MemoryAgentDecision(MemoryAgentAction.NONE, null, "", "", "",
                MemoryPriority.LOW, MemoryCategory.TEMPORARY, 0.0d, reason);
    }
}
