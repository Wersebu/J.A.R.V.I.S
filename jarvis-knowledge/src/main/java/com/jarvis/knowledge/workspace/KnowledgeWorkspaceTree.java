package com.jarvis.knowledge.workspace;

import java.time.Instant;

/**
 * Complete knowledge workspace tree.
 *
 * @param root root node
 * @param generatedAt generation timestamp
 * @param documents indexed document count
 * @param folders folder count
 */
public record KnowledgeWorkspaceTree(
        KnowledgeWorkspaceNode root,
        Instant generatedAt,
        int documents,
        int folders
) {
}
