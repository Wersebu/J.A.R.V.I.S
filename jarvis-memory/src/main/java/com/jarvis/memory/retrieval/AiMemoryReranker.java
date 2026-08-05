package com.jarvis.memory.retrieval;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * GPT-OSS based reranker for ambiguous memory candidates.
 */
@Component
public class AiMemoryReranker implements MemoryReranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiMemoryReranker.class);

    private final List<AIProvider> aiProviders;

    /**
     * Creates the reranker.
     *
     * @param aiProviders available providers
     */
    public AiMemoryReranker(List<AIProvider> aiProviders) {
        this.aiProviders = List.copyOf(aiProviders);
    }

    @Override
    public Optional<UUID> rerank(String query, List<MemoryScore> candidates) {
        if (candidates.size() < 2) {
            return Optional.empty();
        }
        return provider().flatMap(provider -> rerankWithProvider(provider, query, candidates));
    }

    private Optional<AIProvider> provider() {
        return aiProviders.stream()
                .filter(candidate -> candidate.provider().equalsIgnoreCase("ollama"))
                .findFirst();
    }

    private Optional<UUID> rerankWithProvider(AIProvider provider, String query, List<MemoryScore> candidates) {
        try {
            Brain brain = new Brain(BrainType.CLASSIFIER, "ollama", "gpt-oss:20b", "Memory reranker")
                    .withRoutingMetadata("Memory reranking", 0L, ReasoningLevel.LOW);
            String response = provider.chat(brain, prompt(query, candidates), AIJobType.MEMORY_AGENT).response();
            String id = response == null ? "" : response.replaceAll("[^0-9a-fA-F-]", "").strip();
            if (id.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(id));
        } catch (RuntimeException exception) {
            LOGGER.warn("[JARVIS] Memory reranking skipped: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private String prompt(String query, List<MemoryScore> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("Question:\n\n").append(query).append("\n\nCandidate memories:\n\n");
        for (int index = 0; index < candidates.size(); index++) {
            MemoryScore candidate = candidates.get(index);
            builder.append(index + 1).append(".\n")
                    .append("id: ").append(candidate.memory().id()).append("\n")
                    .append(candidate.memory().content()).append("\n\n");
        }
        builder.append("Return ONLY best matching memory id.");
        return builder.toString();
    }
}
