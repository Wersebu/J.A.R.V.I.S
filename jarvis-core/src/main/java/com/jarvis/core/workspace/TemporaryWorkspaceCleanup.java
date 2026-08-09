package com.jarvis.core.workspace;

import com.jarvis.api.service.TemporaryWorkspaceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs startup and periodic cleanup for temporary workspaces.
 */
@Component
public class TemporaryWorkspaceCleanup implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TemporaryWorkspaceCleanup.class);

    private final TemporaryWorkspaceService workspaceService;

    /**
     * Creates the cleanup runner.
     *
     * @param workspaceService workspace service
     */
    public TemporaryWorkspaceCleanup(TemporaryWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info("[WORKSPACE] startup cleanup started");
        int removed = workspaceService.cleanupExpired();
        LOGGER.info("[WORKSPACE] startup cleanup finished removed={}", removed);
    }

    /**
     * Removes expired temporary workspaces.
     */
    @Scheduled(fixedDelayString = "#{@temporaryWorkspaceProperties.cleanupInterval.toMillis()}")
    public void cleanup() {
        int removed = workspaceService.cleanupExpired();
        if (removed > 0) {
            LOGGER.info("[WORKSPACE] periodic cleanup removed={}", removed);
        }
    }
}
