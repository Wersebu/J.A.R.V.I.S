package com.jarvis.knowledge.vault.index;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

/**
 * In-memory view of the persistent chunk index used for searching: term statistics for BM25 and
 * vectors for cosine similarity. It is rebuilt from {@link VaultIndexStore} at startup and kept in
 * sync document by document by the indexer.
 */
public final class VaultSearchIndex {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, StoredDocument> documents = new HashMap<>();
    private final Map<String, String> idByPath = new HashMap<>();
    private final Map<String, List<Entry>> entriesByDocument = new HashMap<>();
    private final TreeMap<String, Integer> documentFrequency = new TreeMap<>();
    private long totalLength;
    private int entryCount;

    /**
     * Replaces the whole index.
     *
     * @param storedDocuments documents
     * @param storedChunks chunks
     */
    public void load(Collection<StoredDocument> storedDocuments, Collection<StoredChunk> storedChunks) {
        lock.writeLock().lock();
        try {
            documents.clear();
            idByPath.clear();
            entriesByDocument.clear();
            documentFrequency.clear();
            totalLength = 0;
            entryCount = 0;
            Map<String, List<StoredChunk>> grouped = new HashMap<>();
            storedChunks.forEach(chunk -> grouped.computeIfAbsent(chunk.documentId(), key -> new ArrayList<>()).add(chunk));
            for (StoredDocument document : storedDocuments) {
                putUnlocked(document, grouped.getOrDefault(document.documentId(), List.of()));
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Adds or replaces a document.
     *
     * @param document document
     * @param chunks its chunks
     */
    public void put(StoredDocument document, List<StoredChunk> chunks) {
        lock.writeLock().lock();
        try {
            removeUnlocked(document.documentId());
            String previousAtPath = idByPath.get(document.path());
            if (previousAtPath != null) {
                removeUnlocked(previousAtPath);
            }
            putUnlocked(document, chunks);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates document metadata without touching chunks.
     *
     * @param document document
     */
    public void updateDocument(StoredDocument document) {
        lock.writeLock().lock();
        try {
            StoredDocument previous = documents.get(document.documentId());
            if (previous != null) {
                idByPath.remove(previous.path());
            }
            documents.put(document.documentId(), document);
            idByPath.put(document.path(), document.documentId());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Updates chunk vectors.
     *
     * @param chunks chunks carrying vectors
     */
    public void updateChunks(Collection<StoredChunk> chunks) {
        lock.writeLock().lock();
        try {
            for (StoredChunk chunk : chunks) {
                List<Entry> entries = entriesByDocument.get(chunk.documentId());
                if (entries == null) {
                    continue;
                }
                for (int index = 0; index < entries.size(); index++) {
                    Entry entry = entries.get(index);
                    if (entry.chunk().chunkId().equals(chunk.chunkId())) {
                        entries.set(index, new Entry(chunk, entry.termFrequencies(), entry.length(), norm(chunk.embedding())));
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a document.
     *
     * @param documentId id
     */
    public void remove(String documentId) {
        lock.writeLock().lock();
        try {
            removeUnlocked(documentId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Runs a read-only function against a consistent view.
     *
     * @param reader function
     * @param <T> result type
     * @return result
     */
    public <T> T read(Function<View, T> reader) {
        lock.readLock().lock();
        try {
            return reader.apply(new View());
        } finally {
            lock.readLock().unlock();
        }
    }

    private void putUnlocked(StoredDocument document, List<StoredChunk> chunks) {
        documents.put(document.documentId(), document);
        idByPath.put(document.path(), document.documentId());
        List<Entry> entries = new ArrayList<>();
        for (StoredChunk chunk : chunks) {
            Map<String, Integer> frequencies = new HashMap<>();
            int length = 0;
            length += addTerms(frequencies, chunk.text(), 1);
            length += addTerms(frequencies, String.join(" ", chunk.headingPath()), 2);
            length += addTerms(frequencies, document.title(), 1);
            length += addTerms(frequencies, String.join(" ", document.tags()) + " " + String.join(" ", document.aliases()), 1);
            entries.add(new Entry(chunk, Map.copyOf(frequencies), Math.max(1, length), norm(chunk.embedding())));
            frequencies.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            totalLength += Math.max(1, length);
            entryCount++;
        }
        entriesByDocument.put(document.documentId(), entries);
    }

    private void removeUnlocked(String documentId) {
        StoredDocument removed = documents.remove(documentId);
        if (removed != null && documentId.equals(idByPath.get(removed.path()))) {
            idByPath.remove(removed.path());
        }
        List<Entry> entries = entriesByDocument.remove(documentId);
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            entry.termFrequencies().keySet().forEach(term -> documentFrequency.computeIfPresent(term, (key, count) -> count <= 1 ? null : count - 1));
            totalLength -= entry.length();
            entryCount--;
        }
    }

    private static int addTerms(Map<String, Integer> frequencies, String text, int weight) {
        int added = 0;
        for (String stem : PolishTextAnalyzer.stems(text)) {
            frequencies.merge(stem, weight, Integer::sum);
            added += weight;
        }
        return added;
    }

    private static float norm(float[] vector) {
        if (vector == null) {
            return 0.0f;
        }
        double sum = 0.0d;
        for (float value : vector) {
            sum += value * value;
        }
        return (float) Math.sqrt(sum);
    }

    /**
     * Indexed chunk with term statistics.
     *
     * @param chunk chunk row
     * @param termFrequencies stem frequencies (text, headings x2, title, tags)
     * @param length weighted token length
     * @param vectorNorm L2 norm of the vector, 0 when none
     */
    public record Entry(StoredChunk chunk, Map<String, Integer> termFrequencies, int length, float vectorNorm) {
    }

    /**
     * Consistent read view.
     */
    public final class View {

        /**
         * Returns all documents.
         *
         * @return documents
         */
        public Collection<StoredDocument> documents() {
            return documents.values();
        }

        /**
         * Finds a document by id.
         *
         * @param documentId id
         * @return document
         */
        public Optional<StoredDocument> document(String documentId) {
            return Optional.ofNullable(documents.get(documentId));
        }

        /**
         * Finds a document by path.
         *
         * @param path path
         * @return document
         */
        public Optional<StoredDocument> documentByPath(String path) {
            String id = idByPath.get(path);
            return id == null ? Optional.empty() : Optional.ofNullable(documents.get(id));
        }

        /**
         * Returns a document's entries.
         *
         * @param documentId id
         * @return entries
         */
        public List<Entry> entries(String documentId) {
            return entriesByDocument.getOrDefault(documentId, List.of());
        }

        /**
         * Returns how many chunks contain a term.
         *
         * @param term stem
         * @return document frequency
         */
        public int documentFrequency(String term) {
            return documentFrequency.getOrDefault(term, 0);
        }

        /**
         * Returns vocabulary terms starting with a prefix.
         *
         * @param prefix prefix
         * @return terms
         */
        public Collection<String> termsWithPrefix(String prefix) {
            return documentFrequency.subMap(prefix, true, prefix + Character.MAX_VALUE, true).keySet();
        }

        /**
         * Returns whether a term exists.
         *
         * @param term stem
         * @return true when indexed
         */
        public boolean hasTerm(String term) {
            return documentFrequency.containsKey(term);
        }

        /**
         * Returns the number of chunks.
         *
         * @return chunk count
         */
        public int entryCount() {
            return entryCount;
        }

        /**
         * Returns the average weighted chunk length.
         *
         * @return average length
         */
        public double averageLength() {
            return entryCount == 0 ? 1.0d : (double) totalLength / entryCount;
        }
    }
}
