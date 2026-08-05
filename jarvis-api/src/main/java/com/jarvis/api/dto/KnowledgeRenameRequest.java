package com.jarvis.api.dto;

/**
 * Request to rename a knowledge node.
 *
 * @param nodeId source node identifier
 * @param newName new node name
 */
public record KnowledgeRenameRequest(String nodeId, String newName) {
}
