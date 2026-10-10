package com.jarvis.knowledge.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Configuration of the Obsidian-compatible knowledge vault.
 *
 * <p>The vault root itself is {@code knowledge.root}: the vault is the existing knowledge
 * directory, not a second store next to it.
 *
 * @param mode retrieval mode; {@link VaultMode#LEGACY} keeps the previous mechanism untouched
 * @param indexDatabase SQLite file holding the rebuildable chunk index (never the source of truth)
 * @param excludeDefaults whether the built-in exclusion list (.obsidian, .git, temp, backups, secrets) applies
 * @param exclude additional exclusion globs relative to the vault root
 * @param promptFiles system prompt files whose content (and copies of it) must never be indexed
 * @param debounceMs quiet period after the last file change before the chunk index is reconciled
 * @param search search settings
 * @param workflow workflow / long document reading settings
 * @param chunking chunking settings
 * @param embedding embedding provider settings
 */
@ConfigurationProperties(prefix = "knowledge.vault")
public record KnowledgeVaultProperties(
        VaultMode mode,
        String indexDatabase,
        Boolean excludeDefaults,
        List<String> exclude,
        List<String> promptFiles,
        int debounceMs,
        Search search,
        Workflow workflow,
        Chunking chunking,
        Embedding embedding
) {

    /**
     * Applies safe defaults.
     */
    public KnowledgeVaultProperties {
        mode = mode == null ? VaultMode.LEGACY : mode;
        indexDatabase = blank(indexDatabase) ? "./data/knowledge-index.db" : indexDatabase;
        excludeDefaults = excludeDefaults == null ? Boolean.TRUE : excludeDefaults;
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        promptFiles = promptFiles == null ? List.of("config/jarvis.md") : List.copyOf(promptFiles);
        debounceMs = debounceMs > 0 ? debounceMs : 1500;
        search = search == null ? new Search(0, 0, 0, null, null, 0) : search;
        workflow = workflow == null ? new Workflow(null, 0, 0, 0) : workflow;
        chunking = chunking == null ? new Chunking(0, 0) : chunking;
        embedding = embedding == null
                ? new Embedding(null, null, null, 0, null, null, 0, null, null, 0, null, null, null)
                : embedding;
    }

    /**
     * Creates default properties (used by tests and the migration CLI).
     *
     * @param mode retrieval mode
     * @return defaults
     */
    public static KnowledgeVaultProperties defaults(VaultMode mode) {
        return new KnowledgeVaultProperties(mode, null, null, null, null, 0, null, null, null, null);
    }

    /**
     * Search settings.
     *
     * @param maxResults maximum fragments returned to the model per search
     * @param maxContextTokens token budget for fragment text returned by one search
     * @param candidatePool lexical / semantic candidates considered before fusion
     * @param minSemanticSimilarity cosine similarity a semantic-only hit must reach (ranking signal, not probability)
     * @param inactiveStatuses frontmatter statuses hidden unless archived documents are requested
     * @param rrfK reciprocal rank fusion constant
     */
    public record Search(
            int maxResults,
            int maxContextTokens,
            int candidatePool,
            Double minSemanticSimilarity,
            List<String> inactiveStatuses,
            int rrfK
    ) {
        /** Applies defaults. */
        public Search {
            maxResults = maxResults > 0 ? maxResults : 6;
            maxContextTokens = maxContextTokens > 0 ? maxContextTokens : 2500;
            candidatePool = candidatePool > 0 ? candidatePool : 50;
            minSemanticSimilarity = minSemanticSimilarity == null ? 0.0d : minSemanticSimilarity;
            inactiveStatuses = inactiveStatuses == null
                    ? List.of("archived", "archive", "inactive", "deprecated", "superseded", "obsolete", "archiwum", "nieaktualny")
                    : List.copyOf(inactiveStatuses);
            rrfK = rrfK > 0 ? rrfK : 60;
        }
    }

    /**
     * Workflow and long document reading settings.
     *
     * @param folders top-level folders whose documents are workflows
     * @param maxPartTokens maximum tokens returned by one READ_WORKFLOW / paged READ_DOCUMENT part
     * @param maxCandidates maximum workflow candidates returned by FIND_WORKFLOW
     * @param maxPartCharacters maximum characters of one part; keeps a part inside the tool loop
     *                          result budget (jarvis.tools.max-result-chars, default 16000) so it is never shortened
     */
    public record Workflow(List<String> folders, int maxPartTokens, int maxCandidates, int maxPartCharacters) {
        /** Applies defaults. */
        public Workflow {
            folders = folders == null ? List.of("workflows") : List.copyOf(folders);
            maxPartTokens = maxPartTokens > 0 ? maxPartTokens : 3500;
            maxCandidates = maxCandidates > 0 ? maxCandidates : 5;
            maxPartCharacters = maxPartCharacters > 0 ? maxPartCharacters : 10_000;
        }

        /**
         * Creates settings with the default character budget.
         *
         * @param folders workflow folders
         * @param maxPartTokens token budget of one part
         * @param maxCandidates candidates
         */
        public Workflow(List<String> folders, int maxPartTokens, int maxCandidates) {
            this(folders, maxPartTokens, maxCandidates, 0);
        }
    }

    /**
     * Chunking settings.
     *
     * @param targetTokens preferred fragment size
     * @param minTokens fragments smaller than this are merged with neighbours of the same section
     */
    public record Chunking(int targetTokens, int minTokens) {
        /** Applies defaults. */
        public Chunking {
            targetTokens = targetTokens > 0 ? targetTokens : 320;
            minTokens = minTokens > 0 ? minTokens : 40;
        }
    }

    /**
     * Embedding provider settings.
     *
     * @param provider {@code none}, {@code core} (existing Core EmbeddingProvider bean), {@code ollama}
     *                 (dedicated Ollama model, CPU by default) or {@code openai} (OpenAI-compatible
     *                 /v1/embeddings, e.g. text-embeddings-inference CPU)
     * @param baseUrl provider base URL (ollama / openai)
     * @param model model name
     * @param dimensions expected vector dimension; 0 accepts the first dimension seen and pins it
     * @param queryPrefix prefix for queries (e5: {@code "query: "})
     * @param passagePrefix prefix for indexed fragments (e5: {@code "passage: "})
     * @param maxInputTokens real input token limit of the embedding model
     * @param tokenizer {@code heuristic} (conservative estimate) or {@code remote} (provider /tokenize endpoint)
     * @param timeout request timeout
     * @param batchSize inputs per embedding request
     * @param forceCpu Ollama only: send num_gpu=0 so the embedding model never takes VRAM from the chat model
     * @param normalize L2-normalize vectors before storing
     * @param apiKey optional bearer token (never logged)
     */
    public record Embedding(
            String provider,
            String baseUrl,
            String model,
            int dimensions,
            String queryPrefix,
            String passagePrefix,
            int maxInputTokens,
            String tokenizer,
            Duration timeout,
            int batchSize,
            Boolean forceCpu,
            Boolean normalize,
            String apiKey
    ) {
        /** Applies defaults. */
        public Embedding {
            provider = blank(provider) ? "none" : provider.trim().toLowerCase(java.util.Locale.ROOT);
            baseUrl = baseUrl == null ? "" : baseUrl.trim();
            model = model == null ? "" : model.trim();
            dimensions = Math.max(0, dimensions);
            queryPrefix = queryPrefix == null ? "" : queryPrefix;
            passagePrefix = passagePrefix == null ? "" : passagePrefix;
            maxInputTokens = maxInputTokens > 0 ? maxInputTokens : 512;
            tokenizer = blank(tokenizer) ? "heuristic" : tokenizer.trim().toLowerCase(java.util.Locale.ROOT);
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
            batchSize = batchSize > 0 ? batchSize : 16;
            forceCpu = forceCpu == null ? Boolean.TRUE : forceCpu;
            normalize = normalize == null ? Boolean.TRUE : normalize;
            apiKey = apiKey == null ? "" : apiKey;
        }

        /**
         * Returns whether embeddings are configured at all.
         *
         * @return true unless provider is none
         */
        public boolean enabled() {
            return !"none".equals(provider);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
