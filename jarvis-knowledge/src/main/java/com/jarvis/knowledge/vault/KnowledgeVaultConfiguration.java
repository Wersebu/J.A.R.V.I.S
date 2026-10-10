package com.jarvis.knowledge.vault;

import com.jarvis.common.embedding.EmbeddingProvider;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.knowledge.vault.embedding.CoreProviderEmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.EmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.OllamaEmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.OpenAiCompatibleEmbeddingBackend;
import com.jarvis.knowledge.vault.embedding.VaultEmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Wires the Obsidian-compatible vault. The vault root is {@code knowledge.root}.
 */
@Configuration
public class KnowledgeVaultConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeVaultConfiguration.class);

    /**
     * Creates the path policy shared by the legacy index, the watcher, the workspace and the vault.
     *
     * @param knowledgeProperties knowledge configuration
     * @param vaultProperties vault configuration
     * @return policy
     */
    @Bean
    public VaultPathPolicy vaultPathPolicy(KnowledgeProperties knowledgeProperties, KnowledgeVaultProperties vaultProperties) {
        PromptFileGuard guard = new PromptFileGuard(vaultProperties.promptFiles().stream().map(Path::of).toList());
        return new VaultPathPolicy(Path.of(knowledgeProperties.root()), vaultProperties.excludeDefaults(), vaultProperties.exclude(), guard);
    }

    /**
     * Creates the vault embedding service from configuration.
     *
     * @param vaultProperties vault configuration
     * @param coreProvider existing Core embedding provider
     * @return embedding service
     */
    @Bean
    public VaultEmbeddingService vaultEmbeddingService(KnowledgeVaultProperties vaultProperties, ObjectProvider<EmbeddingProvider> coreProvider) {
        KnowledgeVaultProperties.Embedding settings = vaultProperties.embedding();
        EmbeddingBackend backend = switch (settings.provider()) {
            case "none", "disabled", "" -> null;
            case "ollama" -> new OllamaEmbeddingBackend(settings.baseUrl(), settings.model(), settings.forceCpu(), settings.timeout());
            case "openai", "openai-compatible", "tei" -> new OpenAiCompatibleEmbeddingBackend(settings.baseUrl(), settings.model(),
                    settings.apiKey(), "remote".equals(settings.tokenizer()), settings.timeout());
            case "core" -> {
                EmbeddingProvider provider = coreProvider.getIfAvailable();
                yield provider == null ? null : new CoreProviderEmbeddingBackend(provider);
            }
            default -> throw new IllegalStateException("Unknown knowledge.vault.embedding.provider: " + settings.provider()
                    + " (use none, core, ollama or openai)");
        };
        LOGGER.info("[VAULT] mode={} embeddingProvider={} model={} maxInputTokens={} tokenizer={}",
                vaultProperties.mode(), settings.provider(), settings.model(), settings.maxInputTokens(), settings.tokenizer());
        return new VaultEmbeddingService(settings, backend);
    }

    /**
     * Creates the vault service.
     *
     * @param vaultProperties vault configuration
     * @param policy path policy
     * @param embeddings embedding service
     * @return vault service
     */
    @Bean(destroyMethod = "close")
    public KnowledgeVaultService knowledgeVaultService(KnowledgeVaultProperties vaultProperties, VaultPathPolicy policy,
                                                       VaultEmbeddingService embeddings) {
        return new KnowledgeVaultService(vaultProperties, policy, embeddings);
    }
}
