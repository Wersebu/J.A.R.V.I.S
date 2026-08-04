package com.jarvis.knowledge.context;

import com.jarvis.common.event.KnowledgeEvent;
import com.jarvis.common.event.KnowledgeEventType;
import com.jarvis.knowledge.KnowledgeEventPublisher;
import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Default context builder for Markdown and plain text knowledge documents.
 */
@Service
public class DefaultContextBuilder implements ContextBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultContextBuilder.class);
    private static final int MAX_CONTEXT_CHARACTERS = 12_000;
    private static final String CONTEXT_BOUNDARY = "========================================";
    private static final String SOURCE_BOUNDARY = "----------------------------------------";

    private final KnowledgeProperties properties;
    private final KnowledgeEventPublisher eventPublisher;

    /**
     * Creates the default context builder.
     *
     * @param properties knowledge configuration
     * @param eventPublisher knowledge event publisher
     */
    public DefaultContextBuilder(KnowledgeProperties properties, KnowledgeEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Builds structured knowledge context from retrieved documents.
     *
     * @param retrievalResult retrieval result
     * @return knowledge context
     */
    @Override
    public KnowledgeContext build(RetrievalResult retrievalResult) {
        long started = System.nanoTime();
        eventPublisher.publish(KnowledgeEvent.context(KnowledgeEventType.CONTEXT_BUILD_STARTED));

        List<KnowledgeSource> sources = new ArrayList<>();
        StringBuilder context = new StringBuilder(CONTEXT_BOUNDARY).append(System.lineSeparator()).append(System.lineSeparator());
        boolean truncated = false;

        for (RetrievalDocument document : documents(retrievalResult)) {
            if (!isSupported(document.relativePath())) {
                continue;
            }
            Path path = resolveKnowledgePath(document.relativePath());
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String content = readContent(path).trim();
            if (content.isBlank()) {
                continue;
            }

            String prefix = "Source: " + sourceName(document.relativePath()) + System.lineSeparator() + System.lineSeparator();
            String suffix = System.lineSeparator().repeat(2) + SOURCE_BOUNDARY + System.lineSeparator().repeat(2);
            int available = MAX_CONTEXT_CHARACTERS - context.length() - suffix.length() - CONTEXT_BOUNDARY.length();
            if (available <= prefix.length()) {
                truncated = true;
                break;
            }

            int availableContentLength = available - prefix.length();
            String usedContent = content.length() <= availableContentLength
                    ? content
                    : content.substring(0, availableContentLength).stripTrailing();
            truncated = truncated || usedContent.length() < content.length();

            context.append(prefix).append(usedContent).append(suffix);
            sources.add(new KnowledgeSource(
                    document.documentId(),
                    document.title(),
                    document.relativePath(),
                    document.category(),
                    usedContent.length()
            ));

            if (truncated) {
                break;
            }
        }

        appendClosingBoundary(context);
        long buildTimeMs = (System.nanoTime() - started) / 1_000_000;
        int totalCharacters = context.length();
        int estimatedTokens = totalCharacters / 4;

        eventPublisher.publish(KnowledgeEvent.context(KnowledgeEventType.CONTEXT_BUILD_FINISHED));
        LOGGER.info("[JARVIS] Context build sources={} characters={} estimatedTokens={} buildTimeMs={} truncated={}",
                sources.size(),
                totalCharacters,
                estimatedTokens,
                buildTimeMs,
                truncated);

        return new KnowledgeContext(
                context.toString(),
                List.copyOf(sources),
                sources.size(),
                totalCharacters,
                estimatedTokens,
                truncated,
                buildTimeMs
        );
    }

    private List<RetrievalDocument> documents(RetrievalResult retrievalResult) {
        if (retrievalResult == null || retrievalResult.documents() == null) {
            return List.of();
        }
        return retrievalResult.documents();
    }

    private boolean isSupported(String relativePath) {
        String extension = extension(relativePath);
        return "md".equals(extension) || "txt".equals(extension);
    }

    private String extension(String relativePath) {
        int index = relativePath.lastIndexOf('.');
        if (index < 0 || index == relativePath.length() - 1) {
            return "";
        }
        return relativePath.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String sourceName(String relativePath) {
        int index = relativePath.lastIndexOf('/');
        return index >= 0 ? relativePath.substring(index + 1) : relativePath;
    }

    private Path resolveKnowledgePath(String relativePath) {
        Path root = Path.of(properties.root()).toAbsolutePath().normalize();
        Path path = root.resolve(relativePath).normalize();
        if (!path.startsWith(root)) {
            throw new KnowledgeException("Rejected knowledge path outside root: " + relativePath);
        }
        return path;
    }

    private String readContent(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new KnowledgeException("Failed to read knowledge source " + path, exception);
        }
    }

    private void appendClosingBoundary(StringBuilder context) {
        context.append(CONTEXT_BOUNDARY);
    }
}
