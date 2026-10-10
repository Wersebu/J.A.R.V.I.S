package com.jarvis.knowledge.vault.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.knowledge.KnowledgeException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Persistent, rebuildable chunk index in a dedicated SQLite file (separate from conversation
 * memory). Markdown files remain the source of truth: deleting this file loses nothing but
 * computed fragments and vectors, which the next scan recreates.
 */
public final class VaultIndexStore implements AutoCloseable {

    private static final int SCHEMA_VERSION = 1;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final Path databaseFile;
    private final ObjectMapper mapper = new ObjectMapper();
    private Connection connection;

    /**
     * Creates the store.
     *
     * @param databaseFile SQLite file
     */
    public VaultIndexStore(Path databaseFile) {
        this.databaseFile = databaseFile.toAbsolutePath().normalize();
    }

    /**
     * Returns the database file.
     *
     * @return file
     */
    public Path databaseFile() {
        return databaseFile;
    }

    /**
     * Opens the database and creates the schema.
     */
    public synchronized void open() {
        if (connection != null) {
            return;
        }
        try {
            if (databaseFile.getParent() != null) {
                Files.createDirectories(databaseFile.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS documents(
                            doc_id TEXT PRIMARY KEY, path TEXT NOT NULL UNIQUE, title TEXT, doc_type TEXT, status TEXT,
                            project TEXT, tags TEXT, aliases TEXT, version TEXT, updated TEXT, id_source TEXT,
                            content_hash TEXT NOT NULL, size INTEGER, mtime INTEGER, line_count INTEGER, token_count INTEGER,
                            indexed_at TEXT, state TEXT, error TEXT, links TEXT, workflow INTEGER)""");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS chunks(
                            chunk_id TEXT PRIMARY KEY,
                            doc_id TEXT NOT NULL REFERENCES documents(doc_id) ON DELETE CASCADE,
                            ord INTEGER, heading_path TEXT, start_line INTEGER, end_line INTEGER, text TEXT NOT NULL,
                            chunk_hash TEXT NOT NULL, doc_hash TEXT, doc_version TEXT, token_count INTEGER,
                            embedding BLOB, embedding_fp TEXT, embedding_dim INTEGER, embedding_error TEXT)""");
                statement.execute("CREATE INDEX IF NOT EXISTS chunks_doc ON chunks(doc_id)");
            }
            String version = meta("schema.version").orElse("");
            if (!version.isEmpty() && !version.equals(String.valueOf(SCHEMA_VERSION))) {
                clearAll();
            }
            putMeta("schema.version", String.valueOf(SCHEMA_VERSION));
        } catch (SQLException | IOException exception) {
            throw new KnowledgeException("Failed to open vault index " + databaseFile, exception);
        }
    }

    /**
     * Reads a meta value.
     *
     * @param key key
     * @return value
     */
    public synchronized Optional<String> meta(String key) {
        try (PreparedStatement statement = connection().prepareStatement("SELECT value FROM meta WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("read meta", exception);
        }
    }

    /**
     * Writes a meta value.
     *
     * @param key key
     * @param value value
     */
    public synchronized void putMeta(String key, String value) {
        try (PreparedStatement statement = connection().prepareStatement(
                "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            statement.setString(1, key);
            statement.setString(2, value == null ? "" : value);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("write meta", exception);
        }
    }

    /**
     * Lists every document row.
     *
     * @return documents
     */
    public synchronized List<StoredDocument> documents() {
        List<StoredDocument> documents = new ArrayList<>();
        try (Statement statement = connection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM documents ORDER BY path")) {
            while (rows.next()) {
                documents.add(document(rows));
            }
            return documents;
        } catch (SQLException exception) {
            throw failure("list documents", exception);
        }
    }

    /**
     * Lists every chunk row.
     *
     * @return chunks
     */
    public synchronized List<StoredChunk> chunks() {
        return queryChunks("SELECT * FROM chunks ORDER BY doc_id, ord", null);
    }

    /**
     * Lists the chunks of one document.
     *
     * @param documentId document id
     * @return chunks in order
     */
    public synchronized List<StoredChunk> chunks(String documentId) {
        return queryChunks("SELECT * FROM chunks WHERE doc_id=? ORDER BY ord", documentId);
    }

    /**
     * Atomically replaces a document and all of its chunks. Any other row occupying the same path
     * is removed first, so re-indexing never produces duplicates.
     *
     * @param document document
     * @param chunks chunks
     */
    public synchronized void replaceDocument(StoredDocument document, List<StoredChunk> chunks) {
        Connection db = connection();
        try {
            db.setAutoCommit(false);
            try (PreparedStatement deleteByPath = db.prepareStatement("DELETE FROM documents WHERE path=? AND doc_id<>?");
                 PreparedStatement deleteChunks = db.prepareStatement("DELETE FROM chunks WHERE doc_id=?")) {
                deleteByPath.setString(1, document.path());
                deleteByPath.setString(2, document.documentId());
                deleteByPath.executeUpdate();
                deleteChunks.setString(1, document.documentId());
                deleteChunks.executeUpdate();
            }
            upsertDocument(db, document);
            insertChunks(db, chunks);
            db.commit();
        } catch (SQLException exception) {
            rollback(db);
            throw failure("replace document " + document.path(), exception);
        } finally {
            autoCommit(db);
        }
    }

    /**
     * Updates document metadata only (e.g. after a move with unchanged content).
     *
     * @param document document
     */
    public synchronized void updateDocument(StoredDocument document) {
        Connection db = connection();
        try {
            db.setAutoCommit(false);
            try (PreparedStatement deleteByPath = db.prepareStatement("DELETE FROM documents WHERE path=? AND doc_id<>?")) {
                deleteByPath.setString(1, document.path());
                deleteByPath.setString(2, document.documentId());
                deleteByPath.executeUpdate();
            }
            upsertDocument(db, document);
            db.commit();
        } catch (SQLException exception) {
            rollback(db);
            throw failure("update document " + document.path(), exception);
        } finally {
            autoCommit(db);
        }
    }

    /**
     * Stores embeddings (or embedding errors) for existing chunks.
     *
     * @param chunks chunks carrying the new embedding state
     */
    public synchronized void updateEmbeddings(Collection<StoredChunk> chunks) {
        Connection db = connection();
        try {
            db.setAutoCommit(false);
            try (PreparedStatement statement = db.prepareStatement(
                    "UPDATE chunks SET embedding=?, embedding_fp=?, embedding_dim=?, embedding_error=? WHERE chunk_id=?")) {
                for (StoredChunk chunk : chunks) {
                    statement.setBytes(1, chunk.embedding() == null ? null : toBytes(chunk.embedding()));
                    statement.setString(2, chunk.embeddingFingerprint());
                    statement.setInt(3, chunk.embeddingDimension());
                    statement.setString(4, chunk.embeddingError());
                    statement.setString(5, chunk.chunkId());
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            db.commit();
        } catch (SQLException exception) {
            rollback(db);
            throw failure("update embeddings", exception);
        } finally {
            autoCommit(db);
        }
    }

    /**
     * Deletes a document and its chunks.
     *
     * @param documentId document id
     */
    public synchronized void deleteDocument(String documentId) {
        try (PreparedStatement chunks = connection().prepareStatement("DELETE FROM chunks WHERE doc_id=?");
             PreparedStatement document = connection().prepareStatement("DELETE FROM documents WHERE doc_id=?")) {
            chunks.setString(1, documentId);
            chunks.executeUpdate();
            document.setString(1, documentId);
            document.executeUpdate();
        } catch (SQLException exception) {
            throw failure("delete document " + documentId, exception);
        }
    }

    /**
     * Drops every vector (used when the embedding model or dimension changes).
     */
    public synchronized void clearEmbeddings() {
        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("UPDATE chunks SET embedding=NULL, embedding_fp='', embedding_dim=0, embedding_error=''");
        } catch (SQLException exception) {
            throw failure("clear embeddings", exception);
        }
    }

    /**
     * Drops every document, chunk and meta row except the schema version.
     */
    public synchronized void clearAll() {
        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("DELETE FROM chunks");
            statement.executeUpdate("DELETE FROM documents");
            statement.executeUpdate("DELETE FROM meta WHERE key<>'schema.version'");
        } catch (SQLException exception) {
            throw failure("clear index", exception);
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing a read-mostly cache
            }
            connection = null;
        }
    }

    private void upsertDocument(Connection db, StoredDocument document) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement("""
                INSERT INTO documents(doc_id, path, title, doc_type, status, project, tags, aliases, version, updated, id_source,
                    content_hash, size, mtime, line_count, token_count, indexed_at, state, error, links, workflow)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(doc_id) DO UPDATE SET path=excluded.path, title=excluded.title, doc_type=excluded.doc_type,
                    status=excluded.status, project=excluded.project, tags=excluded.tags, aliases=excluded.aliases,
                    version=excluded.version, updated=excluded.updated, id_source=excluded.id_source,
                    content_hash=excluded.content_hash, size=excluded.size, mtime=excluded.mtime,
                    line_count=excluded.line_count, token_count=excluded.token_count, indexed_at=excluded.indexed_at,
                    state=excluded.state, error=excluded.error, links=excluded.links, workflow=excluded.workflow""")) {
            int column = 1;
            statement.setString(column++, document.documentId());
            statement.setString(column++, document.path());
            statement.setString(column++, document.title());
            statement.setString(column++, document.type());
            statement.setString(column++, document.status());
            statement.setString(column++, document.project());
            statement.setString(column++, json(document.tags()));
            statement.setString(column++, json(document.aliases()));
            statement.setString(column++, document.version());
            statement.setString(column++, document.updated());
            statement.setString(column++, document.idSource());
            statement.setString(column++, document.contentHash());
            statement.setLong(column++, document.size());
            statement.setLong(column++, document.modifiedMillis());
            statement.setInt(column++, document.lineCount());
            statement.setInt(column++, document.tokenCount());
            statement.setString(column++, document.indexedAt());
            statement.setString(column++, document.state());
            statement.setString(column++, document.error());
            statement.setString(column++, json(document.links()));
            statement.setInt(column, document.workflow() ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertChunks(Connection db, List<StoredChunk> chunks) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement("""
                INSERT INTO chunks(chunk_id, doc_id, ord, heading_path, start_line, end_line, text, chunk_hash, doc_hash,
                    doc_version, token_count, embedding, embedding_fp, embedding_dim, embedding_error)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
            for (StoredChunk chunk : chunks) {
                int column = 1;
                statement.setString(column++, chunk.chunkId());
                statement.setString(column++, chunk.documentId());
                statement.setInt(column++, chunk.ordinal());
                statement.setString(column++, json(chunk.headingPath()));
                statement.setInt(column++, chunk.startLine());
                statement.setInt(column++, chunk.endLine());
                statement.setString(column++, chunk.text());
                statement.setString(column++, chunk.chunkHash());
                statement.setString(column++, chunk.documentHash());
                statement.setString(column++, chunk.documentVersion());
                statement.setInt(column++, chunk.tokenCount());
                statement.setBytes(column++, chunk.embedding() == null ? null : toBytes(chunk.embedding()));
                statement.setString(column++, chunk.embeddingFingerprint());
                statement.setInt(column++, chunk.embeddingDimension());
                statement.setString(column, chunk.embeddingError());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<StoredChunk> queryChunks(String sql, String documentId) {
        List<StoredChunk> chunks = new ArrayList<>();
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            if (documentId != null) {
                statement.setString(1, documentId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    byte[] blob = rows.getBytes("embedding");
                    chunks.add(new StoredChunk(
                            rows.getString("chunk_id"),
                            rows.getString("doc_id"),
                            rows.getInt("ord"),
                            list(rows.getString("heading_path")),
                            rows.getInt("start_line"),
                            rows.getInt("end_line"),
                            rows.getString("text"),
                            rows.getString("chunk_hash"),
                            rows.getString("doc_hash"),
                            rows.getString("doc_version"),
                            rows.getInt("token_count"),
                            blob == null ? null : toFloats(blob),
                            rows.getString("embedding_fp"),
                            rows.getInt("embedding_dim"),
                            rows.getString("embedding_error")
                    ));
                }
            }
            return chunks;
        } catch (SQLException exception) {
            throw failure("list chunks", exception);
        }
    }

    private StoredDocument document(ResultSet rows) throws SQLException {
        return new StoredDocument(
                rows.getString("doc_id"),
                rows.getString("path"),
                rows.getString("title"),
                rows.getString("doc_type"),
                rows.getString("status"),
                rows.getString("project"),
                list(rows.getString("tags")),
                list(rows.getString("aliases")),
                rows.getString("version"),
                rows.getString("updated"),
                rows.getString("id_source"),
                rows.getString("content_hash"),
                rows.getLong("size"),
                rows.getLong("mtime"),
                rows.getInt("line_count"),
                rows.getInt("token_count"),
                rows.getString("indexed_at"),
                rows.getString("state"),
                rows.getString("error"),
                list(rows.getString("links")),
                rows.getInt("workflow") == 1
        );
    }

    private Connection connection() {
        if (connection == null) {
            open();
        }
        return connection;
    }

    private String json(List<String> values) {
        try {
            return mapper.writeValueAsString(values == null ? List.of() : values);
        } catch (IOException exception) {
            return "[]";
        }
    }

    private List<String> list(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (IOException exception) {
            return List.of();
        }
    }

    static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    static float[] toFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int index = 0; index < vector.length; index++) {
            vector[index] = buffer.getFloat();
        }
        return vector;
    }

    private void rollback(Connection db) {
        try {
            db.rollback();
        } catch (SQLException ignored) {
            // the original failure is reported
        }
    }

    private void autoCommit(Connection db) {
        try {
            db.setAutoCommit(true);
        } catch (SQLException ignored) {
            // connection is unusable anyway
        }
    }

    private KnowledgeException failure(String action, SQLException exception) {
        return new KnowledgeException("Vault index failed to " + action + ": " + exception.getMessage(), exception);
    }
}
