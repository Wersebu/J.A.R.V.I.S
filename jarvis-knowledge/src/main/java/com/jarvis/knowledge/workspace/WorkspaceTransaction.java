package com.jarvis.knowledge.workspace;

/**
 * Transaction around one native knowledge tool execution.
 */
public interface WorkspaceTransaction extends AutoCloseable {

    /**
     * Commits the transaction.
     */
    void commit();

    /**
     * Rolls back the transaction.
     */
    void rollback();

    /**
     * Closes the transaction, rolling back if it has not been committed.
     */
    @Override
    void close();
}
