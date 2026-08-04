package com.jarvis.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * Creates the knowledge engine initializer.
     *
     * @param knowledgeService knowledge service
     * @param knowledgeFileWatcher knowledge file watcher
     */
    public KnowledgeEngineInitializer(KnowledgeService knowledgeService, KnowledgeFileWatcher knowledgeFileWatcher) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileWatcher = knowledgeFileWatcher;
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
        LOGGER.info("[JARVIS] Knowledge Engine ready.");
    }
}
