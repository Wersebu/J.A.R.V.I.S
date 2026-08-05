package com.jarvis.knowledge.workspace;

import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.KnowledgeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem snapshot transaction manager for the knowledge workspace.
 */
@Service
public class DefaultWorkspaceTransactionManager implements WorkspaceTransactionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultWorkspaceTransactionManager.class);

    private final KnowledgeProperties knowledgeProperties;
    private final KnowledgeWorkspaceProperties workspaceProperties;

    /**
     * Creates the transaction manager.
     */
    public DefaultWorkspaceTransactionManager(
            KnowledgeProperties knowledgeProperties,
            KnowledgeWorkspaceProperties workspaceProperties
    ) {
        this.knowledgeProperties = knowledgeProperties;
        this.workspaceProperties = workspaceProperties;
    }

    @Override
    public WorkspaceTransaction begin(
            String requestId,
            String conversationId,
            String tool,
            String reason,
            String reasoningSummary
    ) {
        try {
            Path root = rootPath();
            Files.createDirectories(root);
            if (!requiresSnapshot(tool)) {
                String transactionId = "readonly-" + UUID.randomUUID();
                LOGGER.info("[TOOL] Workspace transaction BEGIN id={} tool={} requestId={} snapshot=false",
                        transactionId, tool, requestId);
                return new SnapshotWorkspaceTransaction(transactionId, root, null);
            }
            Path transactionRoot = transactionRoot();
            Files.createDirectories(transactionRoot);
            String transactionId = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-')
                    + "-" + UUID.randomUUID();
            Path snapshot = transactionRoot.resolve(transactionId);
            Files.createDirectories(snapshot);
            copyTree(root, snapshot);
            Files.writeString(snapshot.resolve("transaction.meta"), String.join(System.lineSeparator(),
                    "transactionId=" + transactionId,
                    "timestamp=" + Instant.now(),
                    "requestId=" + safe(requestId),
                    "conversationId=" + safe(conversationId),
                    "tool=" + safe(tool),
                    "reason=" + safe(reason),
                    "reasoningSummary=" + safe(reasoningSummary)
            ), StandardCharsets.UTF_8);
            LOGGER.info("[TOOL] Workspace transaction BEGIN id={} tool={} requestId={} snapshot=true",
                    transactionId, tool, requestId);
            return new SnapshotWorkspaceTransaction(transactionId, root, snapshot);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to begin knowledge workspace transaction", exception);
        }
    }

    private void copyTree(Path sourceRoot, Path targetRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path source : paths.filter(path -> !isInternal(sourceRoot, path)).toList()) {
                Path target = targetRoot.resolve(sourceRoot.relativize(source).toString()).normalize();
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private void restoreTree(Path root, Path snapshot) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(path -> !path.equals(root))
                    .filter(path -> !isInternal(root, path))
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
        copyTree(snapshot, root);
        Files.deleteIfExists(root.resolve("transaction.meta"));
    }

    private void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private boolean isInternal(Path root, Path path) {
        Path relative = root.relativize(path.toAbsolutePath().normalize());
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(workspaceProperties.historyDirectory()) || name.equals(workspaceProperties.draftsDirectory())) {
                return true;
            }
        }
        return false;
    }

    private Path rootPath() {
        return Path.of(knowledgeProperties.root()).toAbsolutePath().normalize();
    }

    private Path transactionRoot() {
        Path path = rootPath().resolve(workspaceProperties.historyDirectory()).resolve("transactions").normalize();
        if (!path.startsWith(rootPath())) {
            throw new KnowledgeException("Transaction directory escapes knowledge root");
        }
        return path;
    }

    private String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    private boolean requiresSnapshot(String tool) {
        String value = tool == null ? "" : tool;
        return value.contains("CREATE_")
                || value.contains("UPDATE_")
                || value.contains("APPEND_")
                || value.contains("DELETE_")
                || value.contains("MOVE_")
                || value.contains("RENAME_");
    }

    private final class SnapshotWorkspaceTransaction implements WorkspaceTransaction {

        private final String id;
        private final Path root;
        private final Path snapshot;
        private boolean completed;

        private SnapshotWorkspaceTransaction(String id, Path root, Path snapshot) {
            this.id = id;
            this.root = root;
            this.snapshot = snapshot;
        }

        @Override
        public void commit() {
            if (completed) {
                return;
            }
            try {
                if (snapshot != null) {
                    deleteTree(snapshot);
                }
                completed = true;
                LOGGER.info("[TOOL] Workspace transaction COMMIT id={}", id);
            } catch (IOException exception) {
                throw new KnowledgeException("Failed to commit workspace transaction " + id, exception);
            }
        }

        @Override
        public void rollback() {
            if (completed) {
                return;
            }
            try {
                if (snapshot != null) {
                    restoreTree(root, snapshot);
                    deleteTree(snapshot);
                }
                completed = true;
                LOGGER.warn("[TOOL] Workspace transaction ROLLBACK id={}", id);
            } catch (IOException exception) {
                throw new KnowledgeException("Failed to rollback workspace transaction " + id, exception);
            }
        }

        @Override
        public void close() {
            if (!completed) {
                rollback();
            }
        }
    }
}
