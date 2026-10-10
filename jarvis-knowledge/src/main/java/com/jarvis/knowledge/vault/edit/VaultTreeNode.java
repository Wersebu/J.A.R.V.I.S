package com.jarvis.knowledge.vault.edit;

import java.util.List;

/**
 * Vault tree node.
 *
 * @param name file or folder name
 * @param path vault-relative path ({@code ""} for the root)
 * @param kind {@code folder}, {@code note} or {@code attachment}
 * @param size file size
 * @param modified last modification (ISO)
 * @param documentId stable document id when indexed
 * @param type document type
 * @param status document status
 * @param indexState chunk index state ({@code INDEXED}, {@code PENDING}, {@code LEGACY}, {@code EXCLUDED_SECRET}, ...)
 * @param workflow whether the note is a workflow
 * @param children children (folders first)
 */
public record VaultTreeNode(
        String name,
        String path,
        String kind,
        long size,
        String modified,
        String documentId,
        String type,
        String status,
        String indexState,
        boolean workflow,
        List<VaultTreeNode> children
) {

    /**
     * Creates an immutable node.
     */
    public VaultTreeNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
