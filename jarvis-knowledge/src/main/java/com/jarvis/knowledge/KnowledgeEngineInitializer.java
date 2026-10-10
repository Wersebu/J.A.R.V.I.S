package com.jarvis.knowledge;

import org.slf4j.Logger;
import com.jarvis.knowledge.vault.KnowledgeVaultService;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Initializes the knowledge engine during application startup.
 */
@Component
public class KnowledgeEngineInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeEngineInitializer.class);

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileWatcher knowledgeFileWatcher;
    private final ObjectProvider<KnowledgeVaultService> vaultService;

    /**
     * Creates the knowledge engine initializer.
     *
     * @param knowledgeService knowledge service
     * @param knowledgeFileWatcher knowledge file watcher
     */
    public KnowledgeEngineInitializer(KnowledgeService knowledgeService, KnowledgeFileWatcher knowledgeFileWatcher) {
        this(knowledgeService, knowledgeFileWatcher, null);
    }

    /**
     * Creates the knowledge engine initializer with the vault chunk index.
     *
     * @param knowledgeService knowledge service
     * @param knowledgeFileWatcher knowledge file watcher
     * @param vaultService vault service (indexes in the background when knowledge.vault.mode=VAULT)
     */
    @Autowired
    public KnowledgeEngineInitializer(KnowledgeService knowledgeService, KnowledgeFileWatcher knowledgeFileWatcher,
                                      ObjectProvider<KnowledgeVaultService> vaultService) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileWatcher = knowledgeFileWatcher;
        this.vaultService = vaultService;
    }

    /**
     * Builds the metadata index and starts the watcher.
     *
     * @param args application arguments
     */
    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info("[JARVIS] Knowledge Engine initializing...");
        LOGGER.info("[JARVIS] Scanning knowledge directory...");
        List<KnowledgeDocument> documents = knowledgeService.reindex();
        LOGGER.info("[JARVIS] Indexed {} documents.", documents.size());
        knowledgeFileWatcher.start();
        KnowledgeVaultService vault = vaultService == null ? null : vaultService.getIfAvailable();
        if (vault != null) {
            vault.start();
            LOGGER.info("[JARVIS] Knowledge vault mode={} (chunk index {}).", vault.properties().mode(),
                    vault.active() ? "indexing in background" : "disabled");
        }
        LOGGER.info("[JARVIS] Knowledge Engine ready.");
    }
}
