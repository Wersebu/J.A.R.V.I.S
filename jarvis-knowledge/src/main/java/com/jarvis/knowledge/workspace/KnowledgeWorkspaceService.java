package com.jarvis.knowledge.workspace;

import com.jarvis.knowledge.retrieval.RetrievalResult;

import java.util.List;
import java.util.UUID;

/**
 * Safe tool API for managing the long-term knowledge workspace.
 */
public interface KnowledgeWorkspaceService {

    /**
     * Lists folders and documents as a tree.
     *
     * @return workspace tree
     */
    KnowledgeWorkspaceTree list();

    /**
     * Searches indexed knowledge.
     *
     * @param query search query
     * @return retrieval result
     */
    RetrievalResult search(String query);

    /**
     * Reads a knowledge document.
     *
     * @param documentId document identifier
     * @return operation result containing content
     */
    KnowledgeToolResult read(UUID documentId);

    /**
     * Reads a knowledge document by logical path.
     *
     * @param logicalPath path relative to the knowledge root
     * @return operation result containing content
     */
    KnowledgeToolResult read(String logicalPath);

    /**
     * Creates a folder.
     */
    KnowledgeToolResult createFolder(String parentId, String name);

    /**
     * Creates a document.
     */
    KnowledgeToolResult createDocument(String parentId, String name, String content);

    /**
     * Replaces document content.
     */
    KnowledgeToolResult updateDocument(UUID documentId, String newContent);

    /**
     * Applies an instruction-based update to a document.
     *
     * @param logicalPath document logical path
     * @param instruction update instruction
     * @param text optional content used by the instruction
     * @return operation result
     */
    KnowledgeToolResult updateDocument(String logicalPath, String instruction, String text);

    /**
     * Appends text to a document.
     */
    KnowledgeToolResult appendDocument(UUID documentId, String text);

    /**
     * Appends text to a document by logical path.
     *
     * @param logicalPath document logical path
     * @param text appended text
     * @return operation result
     */
    KnowledgeToolResult appendDocument(String logicalPath, String text);

    /**
     * Renames a folder or document.
     */
    KnowledgeToolResult rename(String nodeId, String newName);

    /**
     * Moves a folder or document to a new parent folder.
     */
    KnowledgeToolResult move(String nodeId, String newParent);

    /**
     * Deletes a folder or document.
     */
    KnowledgeToolResult delete(String nodeId);

    /**
     * Checks whether a relative path exists under the knowledge root.
     */
    KnowledgeToolResult exists(String path);

    /**
     * Rebuilds the index.
     */
    KnowledgeToolResult index();

    /**
     * Lists stored document versions.
     */
    List<KnowledgeVersion> history(UUID documentId);
}
