package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.KnowledgeChangeListener;
import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.vault.chunk.HeuristicTokenCounter;
import com.jarvis.knowledge.vault.embedding.VaultEmbeddingService;
import com.jarvis.knowledge.vault.index.StoredDocument;
import com.jarvis.knowledge.vault.index.VaultIndexStatus;
import com.jarvis.knowledge.vault.index.VaultIndexStore;
import com.jarvis.knowledge.vault.index.VaultIndexer;
import com.jarvis.knowledge.vault.index.VaultSearchIndex;
import com.jarvis.knowledge.vault.read.PagedDocument;
import com.jarvis.knowledge.vault.read.VaultDocumentReader;
import com.jarvis.knowledge.vault.read.WorkflowCandidate;
import com.jarvis.knowledge.vault.search.VaultSearchHit;
import com.jarvis.knowledge.vault.search.VaultSearchQuery;
import com.jarvis.knowledge.vault.search.VaultSearchResult;
import com.jarvis.knowledge.vault.search.VaultSearchService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Entry point of the Obsidian-compatible vault: chunk index lifecycle, hybrid fragment search,
 * complete / paged document reads, explicit workflow selection, status and used-source tracking.
 *
 * <p>In {@link VaultMode#LEGACY} nothing is indexed or embedded and {@link #active()} is false;
 * callers keep using the previous retrieval path.
 */
public class KnowledgeVaultService implements KnowledgeChangeListener, AutoCloseable {

    private final KnowledgeVaultProperties properties;
    private final VaultPathPolicy policy;
    private final VaultEmbeddingService embeddings;
    private final VaultSearchIndex searchIndex = new VaultSearchIndex();
    private final VaultIndexer indexer;
    private final VaultSearchService searchService;
    private final VaultDocumentReader reader;
    private final VaultSourceTracker sources = new VaultSourceTracker();
    private volatile boolean started;

    /**
     * Creates the service.
     *
     * @param properties vault configuration
     * @param policy path policy (its root is the vault root)
     * @param embeddings embedding service
     */
    public KnowledgeVaultService(KnowledgeVaultProperties properties, VaultPathPolicy policy, VaultEmbeddingService embeddings) {
        this.properties = properties;
        this.policy = policy;
        this.embeddings = embeddings;
        VaultIndexStore store = new VaultIndexStore(Path.of(properties.indexDatabase()));
        this.indexer = new VaultIndexer(policy, store, searchIndex, embeddings, properties);
        this.searchService = new VaultSearchService(searchIndex, embeddings, properties.search());
        this.reader = new VaultDocumentReader(policy, new HeuristicTokenCounter(), properties.workflow().folders());
    }

    /**
     * Returns whether the vault retrieval mode is active.
     *
     * @return true in {@link VaultMode#VAULT}
     */
    public boolean active() {
        return properties.mode() == VaultMode.VAULT;
    }

    /**
     * Starts background indexing when active.
     */
    public synchronized void start() {
        if (!active() || started) {
            return;
        }
        started = true;
        indexer.start();
    }

    /**
     * Opens the index and reconciles synchronously (tests and tooling).
     *
     * @return scan changes
     */
    public synchronized Map<String, Integer> startSynchronously() {
        started = true;
        indexer.open();
        Map<String, Integer> changes = indexer.reconcileNow();
        indexer.embedPendingNow();
        return changes;
    }

    @Override
    public void onKnowledgeChanged(Path path) {
        if (active() && started) {
            indexer.requestReconcile();
        }
    }

    /**
     * Searches fragments and records the sources for the conversation.
     *
     * @param query query
     * @param conversationId conversation receiving the fragments ({@code null} for UI searches)
     * @param requestId request id
     * @return result
     */
    public VaultSearchResult search(VaultSearchQuery query, String conversationId, String requestId) {
        requireActive();
        VaultSearchResult result = searchService.search(query);
        if (conversationId != null) {
            for (VaultSearchHit hit : result.hits()) {
                sources.record(VaultSourceTracker.SourceUse.now(conversationId, requestId, "search", query.text(), hit.path(), hit.title(),
                        String.join(" > ", hit.headingPath()), hit.startLine(), hit.endLine()));
            }
        }
        return result;
    }

    /**
     * Reads a document (complete, or one explicit part when long).
     *
     * @param path path
     * @param part 1-based part
     * @param conversationId conversation receiving the text, or {@code null}
     * @param requestId request id
     * @return part
     */
    public PagedDocument read(String path, int part, String conversationId, String requestId) {
        String cleaned = VaultPathPolicy.clean(path);
        String documentId = indexedDocument(cleaned).map(StoredDocument::documentId).orElse("");
        PagedDocument document = reader.read(cleaned, documentId, part, properties.workflow().maxPartTokens(),
                properties.workflow().maxPartCharacters());
        if (conversationId != null) {
            sources.record(VaultSourceTracker.SourceUse.now(conversationId, requestId, document.workflow() ? "workflow" : "read", "",
                    document.path(), document.title(), document.parts() > 1 ? "part " + document.part() + "/" + document.parts() : "",
                    document.startLine(), document.endLine()));
        }
        return document;
    }

    /**
     * Reads a workflow chosen explicitly as the procedure to execute.
     *
     * @param path workflow path
     * @param part 1-based part
     * @param conversationId conversation
     * @param requestId request
     * @return part
     */
    public PagedDocument readWorkflow(String path, int part, String conversationId, String requestId) {
        String cleaned = VaultPathPolicy.clean(path);
        PagedDocument document = read(cleaned, part, conversationId, requestId);
        if (!document.workflow()) {
            throw new KnowledgeException("Not a workflow document: " + cleaned + ". Workflows have type: workflow in their frontmatter, "
                    + "live in " + properties.workflow().folders() + " or are named *Workflow.md. Use READ_DOCUMENT for ordinary knowledge.");
        }
        return document;
    }

    /**
     * Finds workflow candidates without returning procedure text.
     *
     * @param query what the user wants done ({@code ""} lists every workflow)
     * @param includeArchived include archived workflows
     * @return candidates
     */
    public List<WorkflowCandidate> findWorkflows(String query, boolean includeArchived) {
        requireActive();
        int maxPart = properties.workflow().maxPartTokens();
        int limit = properties.workflow().maxCandidates();
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Set<String>> sections = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) {
            VaultSearchResult result = searchService.search(new VaultSearchQuery(query, VaultSearchQuery.Mode.HYBRID, List.of(), "",
                    List.of(), List.of(), "", includeArchived, true, 40, 1_000_000));
            for (VaultSearchHit hit : result.hits()) {
                scores.merge(hit.documentId(), hit.fusedScore(), Math::max);
                sections.computeIfAbsent(hit.documentId(), key -> new LinkedHashSet<>()).add(String.join(" > ", hit.headingPath()));
            }
        }
        boolean listAll = query == null || query.isBlank();
        return searchIndex.read(view -> {
            List<WorkflowCandidate> candidates = new ArrayList<>();
            for (StoredDocument document : view.documents()) {
                if (!document.workflow() || !StoredDocument.INDEXED.equals(document.state())) {
                    continue;
                }
                if (!includeArchived && VaultSearchService.archived(document, properties.search().inactiveStatuses())) {
                    continue;
                }
                if (!listAll && !scores.containsKey(document.documentId())) {
                    continue;
                }
                int parts = Math.max(1, (int) Math.ceil(document.tokenCount() / (double) maxPart));
                candidates.add(new WorkflowCandidate(document.documentId(), document.path(), document.title(), document.status(),
                        document.version(), document.updated(), document.tokenCount(), parts,
                        scores.getOrDefault(document.documentId(), 0.0d),
                        List.copyOf(sections.getOrDefault(document.documentId(), Set.of()))));
            }
            candidates.sort(Comparator.comparingDouble(WorkflowCandidate::score).reversed().thenComparing(WorkflowCandidate::path));
            return candidates.subList(0, Math.min(candidates.size(), listAll ? 100 : limit));
        });
    }

    /**
     * Returns the index row of a document.
     *
     * @param path path
     * @return document
     */
    public Optional<StoredDocument> indexedDocument(String path) {
        return searchIndex.read(view -> view.documentByPath(path));
    }

    /**
     * Returns every indexed document row.
     *
     * @return documents
     */
    public List<StoredDocument> indexedDocuments() {
        return searchIndex.read(view -> List.copyOf(view.documents()));
    }

    /**
     * Returns the index status.
     *
     * @return status
     */
    public VaultIndexStatus status() {
        VaultIndexStatus status = indexer.status();
        if (active()) {
            return status;
        }
        return new VaultIndexStatus(properties.mode().name(), "DISABLED", 0, 0, 0, 0, 0, 0, List.of(), "", "", 0, Map.of(),
                "Vault chunk index is disabled (knowledge.vault.mode=LEGACY)", embeddings.status(), status.indexDatabase(),
                status.vaultRoot());
    }

    /**
     * Reconciles the index with the files now.
     *
     * @return changes
     */
    public Map<String, Integer> reindex() {
        requireActive();
        Map<String, Integer> changes = indexer.reconcileNow();
        indexer.requestEmbedding();
        return changes;
    }

    /**
     * Drops and rebuilds the index.
     *
     * @return changes
     */
    public Map<String, Integer> rebuild() {
        requireActive();
        return indexer.rebuild();
    }

    /**
     * Embeds pending fragments synchronously.
     *
     * @return embedded fragments
     */
    public int embedPendingNow() {
        return indexer.embedPendingNow();
    }

    /**
     * Returns the source tracker.
     *
     * @return tracker
     */
    public VaultSourceTracker sources() {
        return sources;
    }

    /**
     * Returns the path policy.
     *
     * @return policy
     */
    public VaultPathPolicy policy() {
        return policy;
    }

    /**
     * Returns the configuration.
     *
     * @return properties
     */
    public KnowledgeVaultProperties properties() {
        return properties;
    }

    @Override
    public void close() {
        indexer.close();
    }

    private void requireActive() {
        if (!active()) {
            throw new IllegalStateException("Vault retrieval is disabled (knowledge.vault.mode=LEGACY)");
        }
    }
}
