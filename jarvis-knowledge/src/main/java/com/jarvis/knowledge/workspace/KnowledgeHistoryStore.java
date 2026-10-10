package com.jarvis.knowledge.workspace;

import com.jarvis.knowledge.KnowledgeException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Document version history stored under the knowledge root ({@code .history/<documentId>/}):
 * a copy of the previous content plus a {@code .meta} key/value file per version. Shared by the
 * model-facing workspace tools and the user-facing vault editor so both write one history.
 */
public final class KnowledgeHistoryStore {

    private final Path knowledgeRoot;
    private final Path historyRoot;

    /**
     * Creates the store.
     *
     * @param knowledgeRoot knowledge root
     * @param historyRoot history directory inside the knowledge root
     */
    public KnowledgeHistoryStore(Path knowledgeRoot, Path historyRoot) {
        this.knowledgeRoot = knowledgeRoot.toAbsolutePath().normalize();
        this.historyRoot = historyRoot.toAbsolutePath().normalize();
        if (!this.historyRoot.startsWith(this.knowledgeRoot)) {
            throw new KnowledgeException("History directory escapes the knowledge root: " + historyRoot);
        }
    }

    /**
     * Saves the current content of a file as a version before it is changed.
     *
     * @param documentId document id
     * @param relativePath path relative to the knowledge root
     * @param source file to copy
     * @param author author label ({@code AI} or {@code USER})
     * @param summary change summary
     * @param audit audit metadata
     * @return version id, empty when the source does not exist
     * @throws IOException on filesystem failure
     */
    public Optional<String> save(UUID documentId, String relativePath, Path source, String author, String summary,
                                 KnowledgeWorkspaceAuditContext.Audit audit) throws IOException {
        if (!Files.isRegularFile(source)) {
            return Optional.empty();
        }
        Path folder = historyRoot.resolve(documentId.toString());
        Files.createDirectories(folder);
        String versionId = DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace(':', '-');
        while (Files.exists(folder.resolve(versionId + ".meta"))) {
            versionId = versionId + "-1";
        }
        Path copy = folder.resolve(versionId + "__" + source.getFileName());
        Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(folder.resolve(versionId + ".meta"), String.join(System.lineSeparator(),
                "versionId=" + versionId,
                "documentId=" + documentId,
                "relativePath=" + relativePath,
                "timestamp=" + Instant.now(),
                "author=" + sanitize(author),
                "summary=" + sanitize(summary),
                "conversationId=" + sanitize(audit.conversationId()),
                "requestId=" + sanitize(audit.requestId()),
                "tool=" + sanitize(audit.tool()),
                "reason=" + sanitize(audit.reason()),
                "reasoningSummary=" + sanitize(audit.reasoningSummary()),
                "previousVersionPath=" + knowledgeRoot.relativize(copy).toString().replace('\\', '/')
        ), StandardCharsets.UTF_8);
        return Optional.of(versionId);
    }

    /**
     * Lists versions, newest first.
     *
     * @param documentId document id
     * @return versions
     */
    public List<KnowledgeVersion> list(UUID documentId) {
        Path folder = historyRoot.resolve(documentId.toString());
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(folder)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".meta"))
                    .sorted(Comparator.reverseOrder())
                    .map(this::read)
                    .toList();
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read knowledge history for " + documentId, exception);
        }
    }

    /**
     * Reads the stored content of a version.
     *
     * @param documentId document id
     * @param versionId version id
     * @return content
     */
    public Optional<String> content(UUID documentId, String versionId) {
        if (versionId == null || !versionId.matches("[0-9A-Za-z.\\-]+")) {
            throw new KnowledgeException("Invalid version id: " + versionId);
        }
        return list(documentId).stream()
                .filter(version -> version.versionId().equals(versionId))
                .findFirst()
                .map(version -> {
                    Path copy = knowledgeRoot.resolve(version.previousVersionPath()).normalize();
                    if (!copy.startsWith(historyRoot) || !Files.isRegularFile(copy)) {
                        throw new KnowledgeException("Version content is missing: " + versionId);
                    }
                    try {
                        return Files.readString(copy, StandardCharsets.UTF_8);
                    } catch (IOException exception) {
                        throw new KnowledgeException("Failed to read version " + versionId, exception);
                    }
                });
    }

    private KnowledgeVersion read(Path metadata) {
        try {
            Map<String, String> values = new HashMap<>();
            Files.readAllLines(metadata, StandardCharsets.UTF_8).forEach(line -> {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    values.put(line.substring(0, separator), line.substring(separator + 1));
                }
            });
            return new KnowledgeVersion(
                    values.getOrDefault("versionId", metadata.getFileName().toString()),
                    values.getOrDefault("documentId", ""),
                    values.getOrDefault("relativePath", ""),
                    Instant.parse(values.getOrDefault("timestamp", Instant.EPOCH.toString())),
                    values.getOrDefault("author", "AI"),
                    values.getOrDefault("summary", ""),
                    values.getOrDefault("conversationId", ""),
                    values.getOrDefault("requestId", ""),
                    values.getOrDefault("tool", ""),
                    values.getOrDefault("reason", ""),
                    values.getOrDefault("reasoningSummary", ""),
                    values.getOrDefault("previousVersionPath", "")
            );
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read version metadata " + metadata, exception);
        }
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
