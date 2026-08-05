package com.jarvis.api.dto;

/**
 * Request to move a knowledge node.
 *
 * @param nodeId source node identifier
 * @param newParent target parent node identifier
 */
public record KnowledgeMoveRequest(String nodeId, String newParent) {
}
