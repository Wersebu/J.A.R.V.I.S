package com.jarvis.knowledge.vault.index;

import com.jarvis.knowledge.vault.KnowledgeVaultProperties;
import com.jarvis.knowledge.vault.SecretScanner;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import com.jarvis.knowledge.vault.chunk.MarkdownChunker;
import com.jarvis.knowledge.vault.chunk.VaultChunk;
import com.jarvis.knowledge.vault.embedding.EmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.VaultEmbeddingService;
import com.jarvis.knowledge.vault.note.VaultLinkResolver;
import com.jarvis.knowledge.vault.note.VaultMarkdownParser;
import com.jarvis.knowledge.vault.note.VaultNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Keeps the persistent chunk index in sync with the Markdown files.
 *
 * <p>Every change notification (file watcher, workspace write, REST call) only schedules a
 * debounced reconcile; the reconcile walks the vault, skips files whose size/mtime and then
 * content hash are unchanged, re-chunks changed files, keeps document ids across moves and
 * renames (frontmatter {@code id}, or identical content reappearing under a new path), deletes
 * rows of vanished files, and finally embeds pending fragments in the background. Vectors are
 * reused for fragments whose embedded text did not change, so an edit re-embeds only the
 * fragments it touched. The whole procedure is idempotent: running it twice changes nothing.
 */
public final class VaultIndexer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VaultIndexer.class);
    private static final long MAX_NOTE_BYTES = 4L * 1024 * 1024;
    private static final Set<String> WORKFLOW_TYPES = Set.of("workflow", "procedure", "procedura", "procedury", "playbook");
    private static final Pattern WORKFLOW_FILE = Pattern.compile("(?i).*workflow\\.(md|markdown|txt)$");

    private final VaultPathPolicy policy;
    private final VaultIndexStore store;
    private final VaultSearchIndex searchIndex;
    private final VaultEmbeddingService embeddings;
    private final KnowledgeVaultProperties properties;
    private final VaultMarkdownParser parser = new VaultMarkdownParser();
    private final Map<String, Double> splitFactors = new ConcurrentHashMap<>();
    private final Object scanLock = new Object();
    private final ScheduledExecutorService executor;

    private volatile String state = "STARTING";
    private volatile String lastError = "";
    private volatile Instant lastScanStarted;
    private volatile Instant lastScanFinished;
    private volatile long lastScanMs;
    private volatile Map<String, Integer> lastChanges = Map.of();
    private volatile ScheduledFuture<?> pendingReconcile;
    private volatile ScheduledFuture<?> pendingEmbedding;
    private volatile long embeddingRetryDelayMs = 30_000L;

    /**
     * Creates the indexer.
     *
     * @param policy path policy
     * @param store persistent store
     * @param searchIndex in-memory index
     * @param embeddings embedding service
     * @param properties vault configuration
     */
    public VaultIndexer(VaultPathPolicy policy, VaultIndexStore store, VaultSearchIndex searchIndex,
                        VaultEmbeddingService embeddings, KnowledgeVaultProperties properties) {
        this.policy = policy;
        this.store = store;
        this.searchIndex = searchIndex;
        this.embeddings = embeddings;
        this.properties = properties;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jarvis-vault-indexer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Opens the persistent index, loads it into memory (so search works immediately after a
     * restart), and schedules a background reconcile + embedding pass.
     */
    public void start() {
        open();
        executor.execute(() -> {
            reconcileNow();
            embedPendingNow();
        });
    }

    /**
     * Opens and loads the persistent index without scanning.
     */
    public void open() {
        store.open();
        checkEmbeddingSpace();
        searchIndex.load(store.documents(), store.chunks());
        LOGGER.info("[VAULT] Chunk index loaded db={} documents={}", store.databaseFile(), store.documents().size());
    }

    /**
     * Requests a debounced reconcile.
     */
    public void requestReconcile() {
        ScheduledFuture<?> previous = pendingReconcile;
        if (previous != null) {
            previous.cancel(false);
        }
        pendingReconcile = executor.schedule(() -> {
            reconcileNow();
            embedPendingNow();
        }, properties.debounceMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules embedding of pending fragments in the background.
     */
    public void requestEmbedding() {
        scheduleEmbedding(0);
    }

    /**
     * Drops the persistent index and rebuilds it from the Markdown files.
     *
     * @return scan changes
     */
    public Map<String, Integer> rebuild() {
        synchronized (scanLock) {
            store.clearAll();
            searchIndex.load(List.of(), List.of());
            splitFactors.clear();
            checkEmbeddingSpace();
        }
        Map<String, Integer> changes = reconcileNow();
        scheduleEmbedding(0);
        return changes;
    }

    /**
     * Synchronously reconciles the index with the vault files (no embedding).
     *
     * @return counts of added, changed, moved, removed, unchanged and failed documents
     */
    public Map<String, Integer> reconcileNow() {
        synchronized (scanLock) {
            Instant started = Instant.now();
            lastScanStarted = started;
            state = "SCANNING";
            Map<String, Integer> changes = new LinkedHashMap<>();
            for (String key : List.of("added", "changed", "moved", "removed", "unchanged", "errors")) {
                changes.put(key, 0);
            }
            try {
                scan(changes);
                lastError = "";
                state = "IDLE";
            } catch (RuntimeException exception) {
                lastError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                state = "ERROR";
                LOGGER.warn("[VAULT] Reconcile failed: {}", lastError, exception);
            }
            lastScanFinished = Instant.now();
            lastScanMs = lastScanFinished.toEpochMilli() - started.toEpochMilli();
            lastChanges = Map.copyOf(changes);
            LOGGER.info("[VAULT] Reconcile finished changes={} ms={}", changes, lastScanMs);
            return changes;
        }
    }

    /**
     * Synchronously embeds every pending fragment (used by tests and the background task).
     *
     * @return number of fragments embedded
     */
    public int embedPendingNow() {
        if (!embeddings.enabled()) {
            return 0;
        }
        synchronized (scanLock) {
            String fingerprint = embeddings.fingerprint();
            List<StoredChunk> pending = searchIndex.read(view -> {
                List<StoredChunk> chunks = new ArrayList<>();
                for (StoredDocument document : view.documents()) {
                    if (!StoredDocument.INDEXED.equals(document.state())) {
                        continue;
                    }
                    for (VaultSearchIndex.Entry entry : view.entries(document.documentId())) {
                        StoredChunk chunk = entry.chunk();
                        boolean embedded = embeddings.dimension() > 0 && chunk.embeddedIn(fingerprint, embeddings.dimension());
                        if (!embedded && !chunk.embeddingError().startsWith("too-long")) {
                            chunks.add(chunk);
                        }
                    }
                }
                return chunks;
            });
            if (pending.isEmpty()) {
                return 0;
            }
            state = "EMBEDDING";
            int embedded = 0;
            Set<String> documentsToResplit = new HashSet<>();
            int batchSize = Math.max(1, properties.embedding().batchSize());
            try {
                for (int start = 0; start < pending.size(); start += batchSize) {
                    List<StoredChunk> batch = pending.subList(start, Math.min(pending.size(), start + batchSize));
                    embedded += embedBatch(batch, fingerprint, documentsToResplit);
                }
                embeddingRetryDelayMs = 30_000L;
            } catch (EmbeddingBackend.EmbeddingException exception) {
                lastError = "Embedding provider unavailable: " + exception.getMessage();
                LOGGER.warn("[VAULT] {} - {} fragment(s) stay keyword-searchable; retry in {} s", lastError,
                        pending.size() - embedded, embeddingRetryDelayMs / 1000);
                scheduleEmbedding(embeddingRetryDelayMs);
                embeddingRetryDelayMs = Math.min(embeddingRetryDelayMs * 2, 600_000L);
            } finally {
                if (embeddings.dimension() > 0) {
                    store.putMeta("embedding.dimension", String.valueOf(embeddings.dimension()));
                }
                state = "IDLE";
            }
            for (String documentId : documentsToResplit) {
                resplit(documentId);
            }
            return embedded;
        }
    }

    /**
     * Returns the indexer status.
     *
     * @return status
     */
    public VaultIndexStatus status() {
        String fingerprint = embeddings.fingerprint();
        int dimension = embeddings.dimension();
        return searchIndex.read(view -> {
            int documents = 0;
            int searchable = 0;
            int chunks = 0;
            int embedded = 0;
            int excluded = 0;
            List<Map<String, String>> errors = new ArrayList<>();
            for (StoredDocument document : view.documents()) {
                documents++;
                if (StoredDocument.INDEXED.equals(document.state())) {
                    searchable++;
                }
                if (document.state().startsWith("EXCLUDED")) {
                    excluded++;
                }
                if ((StoredDocument.ERROR.equals(document.state()) || !nullToEmpty(document.error()).isBlank()) && errors.size() < 50) {
                    errors.add(Map.of("path", document.path(), "state", document.state(), "error", nullToEmpty(document.error())));
                }
                for (VaultSearchIndex.Entry entry : view.entries(document.documentId())) {
                    chunks++;
                    if (dimension > 0 && entry.chunk().embeddedIn(fingerprint, dimension)) {
                        embedded++;
                    }
                }
            }
            return new VaultIndexStatus(properties.mode().name(), state, documents, searchable, chunks, embedded,
                    embeddings.enabled() ? chunks - embedded : 0, excluded, errors,
                    lastScanStarted == null ? "" : lastScanStarted.toString(),
                    lastScanFinished == null ? "" : lastScanFinished.toString(),
                    lastScanMs, lastChanges, lastError, embeddings.status(), store.databaseFile().toString(),
                    policy.root().toString());
        });
    }

    /**
     * Returns whether a stored document is a workflow according to its type, folder or name.
     *
     * @param relativePath path
     * @param type frontmatter type
     * @param workflowFolders workflow folders
     * @return true for workflows
     */
    public static boolean isWorkflow(String relativePath, String type, List<String> workflowFolders) {
        if (type != null && WORKFLOW_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
            return true;
        }
        String firstSegment = relativePath.contains("/") ? relativePath.substring(0, relativePath.indexOf('/')) : "";
        for (String folder : workflowFolders) {
            if (!firstSegment.isEmpty() && firstSegment.equalsIgnoreCase(folder)) {
                return true;
            }
        }
        return WORKFLOW_FILE.matcher(relativePath).matches();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        store.close();
    }

    private void scan(Map<String, Integer> changes) {
        Map<String, FileEntry> files = walk();
        Map<String, StoredDocument> storedByPath = new HashMap<>();
        Map<String, StoredDocument> storedById = new HashMap<>();
        for (StoredDocument document : store.documents()) {
            storedByPath.put(document.path(), document);
            storedById.put(document.documentId(), document);
        }
        VaultLinkResolver resolver = new VaultLinkResolver(files.values().stream().map(FileEntry::path).toList());
        Set<String> claimedIds = new HashSet<>();
        List<Pending> pending = new ArrayList<>();
        for (FileEntry file : files.values()) {
            if (!file.note()) {
                continue;
            }
            StoredDocument stored = storedByPath.get(file.path());
            if (stored != null && stored.size() == file.size() && stored.modifiedMillis() == file.modifiedMillis()
                    && !StoredDocument.ERROR.equals(stored.state())) {
                claimedIds.add(stored.documentId());
                changes.merge("unchanged", 1, Integer::sum);
                continue;
            }
            byte[] bytes;
            try {
                bytes = file.size() > MAX_NOTE_BYTES ? null : Files.readAllBytes(file.absolute());
            } catch (IOException exception) {
                bytes = null;
            }
            String hash = bytes == null ? "" : sha256(bytes);
            if (stored != null && !hash.isEmpty() && hash.equals(stored.contentHash()) && !StoredDocument.ERROR.equals(stored.state())) {
                StoredDocument touched = stored.movedTo(stored.path(), file.modifiedMillis());
                store.updateDocument(touched);
                searchIndex.updateDocument(touched);
                claimedIds.add(stored.documentId());
                changes.merge("unchanged", 1, Integer::sum);
                continue;
            }
            pending.add(new Pending(file, bytes, hash, stored));
        }

        Map<String, StoredDocument> vanished = new LinkedHashMap<>();
        for (StoredDocument stored : storedByPath.values()) {
            FileEntry file = files.get(stored.path());
            if (file == null || !file.note()) {
                vanished.put(stored.documentId(), stored);
            }
        }
        Map<String, StoredDocument> vanishedByHash = new HashMap<>();
        vanished.values().forEach(document -> vanishedByHash.putIfAbsent(document.contentHash(), document));

        for (Pending item : pending) {
            try {
                indexPending(item, storedById, vanished, vanishedByHash, claimedIds, resolver, changes);
            } catch (RuntimeException exception) {
                changes.merge("errors", 1, Integer::sum);
                LOGGER.warn("[VAULT] Failed to index {}: {}", item.file().path(), exception.getMessage(), exception);
            }
        }
        for (StoredDocument document : vanished.values()) {
            store.deleteDocument(document.documentId());
            searchIndex.remove(document.documentId());
            changes.merge("removed", 1, Integer::sum);
        }
    }

    private void indexPending(Pending item, Map<String, StoredDocument> storedById, Map<String, StoredDocument> vanished,
                              Map<String, StoredDocument> vanishedByHash, Set<String> claimedIds, VaultLinkResolver resolver,
                              Map<String, Integer> changes) {
        FileEntry file = item.file();
        if (item.bytes() == null) {
            String id = item.stored() != null ? item.stored().documentId() : newId(claimedIds);
            claimedIds.add(id);
            String reason = file.size() > MAX_NOTE_BYTES ? "File too large to index (> 4 MB)" : "File could not be read";
            storeDocument(errorDocument(id, file, "", reason), List.of());
            changes.merge("errors", 1, Integer::sum);
            return;
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(item.bytes()))
                    .toString();
        } catch (CharacterCodingException exception) {
            String id = item.stored() != null ? item.stored().documentId() : newId(claimedIds);
            claimedIds.add(id);
            storeDocument(errorDocument(id, file, item.hash(), "File is not valid UTF-8; save it as UTF-8 to index it"), List.of());
            changes.merge("errors", 1, Integer::sum);
            return;
        }
        VaultNote note = parser.parse(file.path(), content);

        String id;
        String idSource;
        String warning = note.frontmatterError();
        String frontmatterId = note.frontmatterId();
        boolean moved = false;
        if (!frontmatterId.isBlank()) {
            idSource = "frontmatter";
            id = frontmatterId;
            if (claimedIds.contains(id)) {
                id = frontmatterId + "#" + sha256(file.path().getBytes(StandardCharsets.UTF_8)).substring(0, 8);
                warning = join(warning, "Duplicate frontmatter id '" + frontmatterId + "' - another note already uses it; this note is indexed as " + id);
            }
            StoredDocument previous = storedById.get(id);
            if (previous != null && !previous.path().equals(file.path())) {
                moved = vanished.remove(id) != null;
            }
        } else {
            idSource = "index";
            if (item.stored() != null) {
                id = item.stored().documentId();
            } else {
                StoredDocument sameContent = vanishedByHash.get(item.hash());
                if (sameContent != null && vanished.containsKey(sameContent.documentId()) && !claimedIds.contains(sameContent.documentId())) {
                    id = sameContent.documentId();
                    vanished.remove(id);
                    vanishedByHash.remove(item.hash());
                    moved = true;
                } else {
                    id = newId(claimedIds);
                }
            }
        }
        claimedIds.add(id);
        indexNote(id, idSource, note, content, item.hash(), file, resolver, warning);
        changes.merge(moved ? "moved" : item.stored() == null ? "added" : "changed", 1, Integer::sum);
    }

    private void indexNote(String id, String idSource, VaultNote note, String content, String hash, FileEntry file,
                           VaultLinkResolver resolver, String warning) {
        boolean workflow = isWorkflow(file.path(), note.type(), properties.workflow().folders());
        List<String> links = note.links().stream()
                .map(link -> link.kind() + "|" + link.target() + (link.anchor().isBlank() ? "" : "#" + link.anchor()) + "|"
                        + resolver.resolve(file.path(), link).orElse(""))
                .distinct()
                .limit(500)
                .toList();
        String documentState = StoredDocument.INDEXED;
        String error = warning == null ? "" : warning;
        if (policy.promptGuard() != null && policy.promptGuard().isPromptContent(content)) {
            documentState = StoredDocument.EXCLUDED_PROMPT;
            error = join(error, "Copy of the system prompt - never indexed as knowledge");
        } else {
            var secret = SecretScanner.detect(content);
            if (secret.isPresent()) {
                documentState = StoredDocument.EXCLUDED_SECRET;
                error = join(error, "Looks like it contains a credential (" + secret.get() + ") - not indexed; move secrets out of the vault");
            } else if (note.body().isBlank()) {
                documentState = StoredDocument.EMPTY;
            }
        }
        List<StoredChunk> chunks = new ArrayList<>();
        int tokenCount = embeddings.tokenCounter().count(content);
        if (StoredDocument.INDEXED.equals(documentState)) {
            double factor = splitFactors.getOrDefault(id, 1.0d);
            int maxTokens = Math.max(32, (int) Math.floor(embeddings.maxInputTokens() * factor));
            MarkdownChunker chunker = new MarkdownChunker(embeddings.tokenCounter(), properties.chunking().targetTokens(),
                    maxTokens, properties.chunking().minTokens());
            Map<String, StoredChunk> previous = new HashMap<>();
            for (StoredChunk old : store.chunks(id)) {
                previous.putIfAbsent(old.chunkHash(), old);
            }
            String fingerprint = embeddings.fingerprint();
            for (VaultChunk chunk : chunker.chunk(note, embeddings.passagePrefix())) {
                String chunkHash = sha256(chunk.embeddingText().getBytes(StandardCharsets.UTF_8));
                String chunkId = sha256((id + "|" + chunk.ordinal() + "|" + chunkHash).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
                StoredChunk reused = previous.get(chunkHash);
                float[] vector = reused != null && embeddings.dimension() > 0 && reused.embeddedIn(fingerprint, embeddings.dimension())
                        ? reused.embedding() : null;
                chunks.add(new StoredChunk(chunkId, id, chunk.ordinal(), chunk.headingPath(), chunk.startLine(), chunk.endLine(),
                        chunk.text(), chunkHash, hash, note.version(), chunk.tokenCount(), vector,
                        vector == null ? "" : fingerprint, vector == null ? 0 : vector.length, ""));
            }
            if (chunks.isEmpty()) {
                documentState = StoredDocument.EMPTY;
            }
        }
        StoredDocument document = new StoredDocument(id, file.path(), note.title(), note.type(), note.status(), note.project(),
                note.tags(), note.aliases(), note.version(), note.updated(), idSource, hash, file.size(), file.modifiedMillis(),
                note.lines().size(), tokenCount, Instant.now().toString(), documentState, error, links, workflow);
        storeDocument(document, chunks);
    }

    private void storeDocument(StoredDocument document, List<StoredChunk> chunks) {
        store.replaceDocument(document, chunks);
        searchIndex.put(document, chunks);
    }

    private int embedBatch(List<StoredChunk> batch, String fingerprint, Set<String> resplit) {
        List<String> texts = batch.stream().map(this::embeddingText).toList();
        try {
            List<float[]> vectors = embeddings.embedPassages(texts);
            List<StoredChunk> updated = new ArrayList<>(batch.size());
            for (int index = 0; index < batch.size(); index++) {
                updated.add(batch.get(index).withEmbedding(vectors.get(index), fingerprint));
            }
            store.updateEmbeddings(updated);
            searchIndex.updateChunks(updated);
            return updated.size();
        } catch (EmbeddingBackend.InputTooLongException exception) {
            if (batch.size() > 1) {
                int embedded = 0;
                for (StoredChunk chunk : batch) {
                    embedded += embedBatch(List.of(chunk), fingerprint, resplit);
                }
                return embedded;
            }
            StoredChunk failed = batch.getFirst().withEmbeddingError("too-long: " + exception.getMessage());
            store.updateEmbeddings(List.of(failed));
            searchIndex.updateChunks(List.of(failed));
            resplit.add(failed.documentId());
            return 0;
        }
    }

    private void resplit(String documentId) {
        double factor = splitFactors.merge(documentId, 0.7d, (current, step) -> current * step);
        if (factor < 0.2d) {
            LOGGER.warn("[VAULT] Document {} still exceeds the embedding limit after re-splitting; its oversized fragments stay keyword-only", documentId);
            return;
        }
        searchIndex.read(view -> view.document(documentId)).ifPresent(document -> {
            LOGGER.info("[VAULT] Re-splitting {} with {}% of the token limit after the provider rejected a fragment as too long",
                    document.path(), Math.round(factor * 100));
            StoredDocument forced = new StoredDocument(document.documentId(), document.path(), document.title(), document.type(),
                    document.status(), document.project(), document.tags(), document.aliases(), document.version(),
                    document.updated(), document.idSource(), "", -1, -1, document.lineCount(),
                    document.tokenCount(), document.indexedAt(), document.state(), document.error(), document.links(), document.workflow());
            store.updateDocument(forced);
            searchIndex.updateDocument(forced);
        });
        requestReconcile();
    }

    private String embeddingText(StoredChunk chunk) {
        StoredDocument document = searchIndex.read(view -> view.document(chunk.documentId())).orElse(null);
        String title = document == null ? "" : document.title();
        List<String> parts = new ArrayList<>();
        if (!title.isBlank() && (chunk.headingPath().isEmpty() || !chunk.headingPath().getFirst().equalsIgnoreCase(title))) {
            parts.add(title);
        }
        parts.addAll(chunk.headingPath());
        // Must match MarkdownChunker's embedding text so chunk hashes and reuse stay consistent.
        return embeddings.passagePrefix() + String.join(" > ", parts) + "\n\n" + chunk.text();
    }

    private void scheduleEmbedding(long delayMs) {
        ScheduledFuture<?> previous = pendingEmbedding;
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }
        pendingEmbedding = executor.schedule(this::embedPendingNow, delayMs, TimeUnit.MILLISECONDS);
    }

    private void checkEmbeddingSpace() {
        if (!embeddings.enabled()) {
            return;
        }
        String fingerprint = embeddings.fingerprint();
        String storedFingerprint = store.meta("embedding.fingerprint").orElse("");
        int storedDimension = parseInt(store.meta("embedding.dimension").orElse("0"));
        boolean modelChanged = !storedFingerprint.isEmpty() && !storedFingerprint.equals(fingerprint);
        boolean dimensionChanged = properties.embedding().dimensions() > 0 && storedDimension > 0
                && storedDimension != properties.embedding().dimensions();
        if (modelChanged || dimensionChanged) {
            LOGGER.warn("[VAULT] Embedding space changed (was '{}' dim {}, now '{}'); dropping old vectors - fragments are re-embedded",
                    storedFingerprint, storedDimension, fingerprint);
            store.clearEmbeddings();
            store.putMeta("embedding.dimension", "0");
        } else if (storedDimension > 0) {
            embeddings.pinDimension(storedDimension);
        }
        store.putMeta("embedding.fingerprint", fingerprint);
    }

    private Map<String, FileEntry> walk() {
        Path root = policy.root();
        Map<String, FileEntry> files = new LinkedHashMap<>();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && policy.isExcluded(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (policy.isExcluded(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attributes.isSymbolicLink() && !policy.isSafeRegularFile(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attributes.isRegularFile() && !attributes.isSymbolicLink()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relative = policy.relativize(file);
                    try {
                        BasicFileAttributes real = Files.readAttributes(file, BasicFileAttributes.class);
                        files.put(relative, new FileEntry(relative, file, real.size(), real.lastModifiedTime().toMillis(), policy.isNote(relative)));
                    } catch (IOException exception) {
                        LOGGER.debug("[VAULT] Skipping unreadable file {}", relative);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to scan vault " + root + ": " + exception.getMessage(), exception);
        }
        return files;
    }

    private StoredDocument errorDocument(String id, FileEntry file, String hash, String error) {
        return new StoredDocument(id, file.path(), VaultMarkdownParser.fileTitle(file.path()), "", "", "", List.of(), List.of(), "", "",
                "index", hash, file.size(), file.modifiedMillis(), 0, 0, Instant.now().toString(), StoredDocument.ERROR, error,
                List.of(), false);
    }

    private static String newId(Set<String> claimed) {
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (claimed.contains(id));
        return id;
    }

    private static String join(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return first + "; " + second;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Returns the SHA-256 hex digest of bytes.
     *
     * @param bytes bytes
     * @return hex digest
     */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record FileEntry(String path, Path absolute, long size, long modifiedMillis, boolean note) {
    }

    private record Pending(FileEntry file, byte[] bytes, String hash, StoredDocument stored) {
    }
}
