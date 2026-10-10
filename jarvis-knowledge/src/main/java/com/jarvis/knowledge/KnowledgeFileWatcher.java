package com.jarvis.knowledge;

import org.slf4j.Logger;
import com.jarvis.knowledge.vault.VaultPathPolicy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Watches the knowledge root and keeps the metadata index fresh.
 */
@Service
public class KnowledgeFileWatcher implements SmartLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeFileWatcher.class);

    private final KnowledgeProperties properties;
    private final KnowledgeService knowledgeService;
    private final SupportedFileTypes supportedFileTypes;
    private final Map<WatchKey, Path> directoriesByKey = new ConcurrentHashMap<>();
    private final Map<Path, ScheduledFuture<?>> pendingEvents = new ConcurrentHashMap<>();
    private final ObjectProvider<VaultPathPolicy> pathPolicy;

    private volatile boolean running;
    private WatchService watchService;
    private Thread watcherThread;
    private ScheduledExecutorService debounceExecutor;

    /**
     * Creates the knowledge file watcher.
     *
     * @param properties knowledge configuration
     * @param knowledgeService knowledge service
     * @param supportedFileTypes supported file type detector
     */
    public KnowledgeFileWatcher(
            KnowledgeProperties properties,
            KnowledgeService knowledgeService,
            SupportedFileTypes supportedFileTypes
    ) {
        this(properties, knowledgeService, supportedFileTypes, null);
    }

    /**
     * Creates the knowledge file watcher with vault exclusions.
     *
     * @param properties knowledge configuration
     * @param knowledgeService knowledge service
     * @param supportedFileTypes supported file type detector
     * @param pathPolicy vault path policy; excluded folders such as .obsidian and .git are not watched
     */
    @Autowired
    public KnowledgeFileWatcher(
            KnowledgeProperties properties,
            KnowledgeService knowledgeService,
            SupportedFileTypes supportedFileTypes,
            ObjectProvider<VaultPathPolicy> pathPolicy
    ) {
        this.properties = properties;
        this.knowledgeService = knowledgeService;
        this.supportedFileTypes = supportedFileTypes;
        this.pathPolicy = pathPolicy;
    }

    /**
     * Starts the watcher when enabled.
     */
    @Override
    public void start() {
        if (!properties.watch() || running) {
            return;
        }
        try {
            Path root = Path.of(properties.root()).toAbsolutePath().normalize();
            Files.createDirectories(root);
            watchService = FileSystems.getDefault().newWatchService();
            debounceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jarvis-knowledge-debounce");
                thread.setDaemon(true);
                return thread;
            });
            registerRecursively(root);
            running = true;
            watcherThread = new Thread(this::watchLoop, "jarvis-knowledge-watch");
            watcherThread.setDaemon(true);
            watcherThread.start();
            LOGGER.info("[JARVIS] WatchService started. root={}", root);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to start knowledge file watcher", exception);
        }
    }

    /**
     * Stops the watcher.
     */
    @Override
    public void stop() {
        running = false;
        directoriesByKey.clear();
        pendingEvents.values().forEach(future -> future.cancel(false));
        pendingEvents.clear();
        if (debounceExecutor != null) {
            debounceExecutor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException exception) {
                LOGGER.warn("[JARVIS] Failed to close knowledge watcher: {}", exception.getMessage());
            }
        }
    }

    /**
     * Reports whether the watcher is running.
     *
     * @return true when running
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Disables automatic Spring lifecycle startup so the knowledge initializer
     * can build the index before watching starts.
     *
     * @return false because startup is coordinated explicitly
     */
    @Override
    public boolean isAutoStartup() {
        return false;
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.take();
                Path directory = directoriesByKey.get(key);
                if (directory != null) {
                    handleEvents(key, directory);
                }
                if (!key.reset()) {
                    directoriesByKey.remove(key);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                running = false;
            } catch (IOException exception) {
                LOGGER.warn("[JARVIS] Knowledge watcher error: {}", exception.getMessage(), exception);
            } catch (RuntimeException exception) {
                LOGGER.warn("[JARVIS] Knowledge watcher ignored event error: {}", exception.getMessage(), exception);
            }
        }
    }

    private void handleEvents(WatchKey key, Path directory) throws IOException {
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == OVERFLOW) {
                LOGGER.warn("[JARVIS] Knowledge watcher overflow detected");
                continue;
            }
            Path changedPath = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
            if (event.kind() == ENTRY_CREATE && Files.isDirectory(changedPath)) {
                if (isWorkspaceInternal(changedPath)) {
                    continue;
                }
                registerRecursively(changedPath);
                LOGGER.info("[KNOWLEDGE_WATCHER] CREATE path={}", changedPath);
                // A folder moved or renamed in Obsidian arrives as a new directory full of files
                // that produce no individual events: rescan instead of missing them.
                scheduleFullReindex("CREATE_DIRECTORY");
                continue;
            }
            if (isWorkspaceInternal(changedPath)) {
                continue;
            }
            if (!supportedFileTypes.supports(changedPath)) {
                if (event.kind() == ENTRY_DELETE && !hasExtension(changedPath)) {
                    // Probably a deleted or moved-away folder; its files produce no events of their own.
                    scheduleFullReindex("DELETE_DIRECTORY");
                }
                continue;
            }
            if (event.kind() == ENTRY_DELETE) {
                schedule(changedPath, () -> knowledgeService.removeFile(changedPath), "DELETE");
            } else if (event.kind() == ENTRY_CREATE) {
                schedule(changedPath, () -> knowledgeService.indexFile(changedPath, DocumentStatus.NEW), "CREATE");
            } else if (event.kind() == ENTRY_MODIFY) {
                schedule(changedPath, () -> knowledgeService.indexFile(changedPath, DocumentStatus.UPDATED), "MODIFY");
            }
        }
    }

    private void schedule(Path path, Runnable action, String eventType) {
        ScheduledFuture<?> previous = pendingEvents.remove(path);
        if (previous != null) {
            previous.cancel(false);
            LOGGER.info("[KNOWLEDGE_WATCHER] DEBOUNCED path={} event={}", path, eventType);
        } else {
            LOGGER.info("[KNOWLEDGE_WATCHER] {} path={}", eventType, path);
        }
        ScheduledFuture<?> future = debounceExecutor.schedule(() -> {
            try {
                action.run();
            } finally {
                pendingEvents.remove(path);
            }
        }, properties.watcherDebounceMs(), TimeUnit.MILLISECONDS);
        pendingEvents.put(path, future);
    }

    private void scheduleFullReindex(String reason) {
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        schedule(root, knowledgeService::reindex, reason);
    }

    private boolean hasExtension(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1;
    }

    private void registerRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            paths.filter(Files::isDirectory).forEach(this::registerDirectory);
        }
    }

    private void registerDirectory(Path directory) {
        if (isWorkspaceInternal(directory)) {
            return;
        }
        try {
            WatchKey key = directory.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            directoriesByKey.put(key, directory);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to watch knowledge directory " + directory, exception);
        }
    }

    private boolean isWorkspaceInternal(Path path) {
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(root)) {
            return false;
        }
        VaultPathPolicy policy = pathPolicy == null ? null : pathPolicy.getIfAvailable();
        if (policy != null && !normalizedPath.equals(root) && policy.isExcluded(normalizedPath)) {
            return true;
        }
        Path relative = root.relativize(normalizedPath);
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals(".history") || name.equals(".drafts")) {
                return true;
            }
        }
        return false;
    }
}
