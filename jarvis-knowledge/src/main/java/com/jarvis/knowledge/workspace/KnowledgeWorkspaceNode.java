package com.jarvis.knowledge.workspace;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Node returned by the knowledge workspace tree API.
 *
 * @param nodeId stable logical node identifier
 * @param documentId indexed document identifier, when this is a document
 * @param type node type
 * @param name display name
 * @param relativePath path relative to knowledge root
 * @param children nested child nodes
 * @param fileSize file size for documents
 * @param modified last modified timestamp when available
 * @param indexed whether the node is indexed
 */
public record KnowledgeWorkspaceNode(
        String nodeId,
        UUID documentId,
        KnowledgeNodeType type,
        String name,
        String relativePath,
        List<KnowledgeWorkspaceNode> children,
        long fileSize,
        Instant modified,
        boolean indexed
) {

    /**
     * Creates a node with immutable children.
     */
    public KnowledgeWorkspaceNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
