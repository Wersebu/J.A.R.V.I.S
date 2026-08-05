package com.jarvis.knowledge.workspace;

/**
 * Type of a node in the knowledge workspace tree.
 */
public enum KnowledgeNodeType {
    /** Synthetic root node. */
    ROOT,
    /** Folder under the knowledge root. */
    FOLDER,
    /** Indexed or indexable document. */
    DOCUMENT
}
