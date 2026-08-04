package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.prompt.PromptBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Builds the provider prompt.
 */
@Service
@Order(80)
public class PromptBuilderStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(PromptBuilderStage.class);

    private final PromptBuilder promptBuilder;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the prompt builder stage.
     *
     * @param promptBuilder prompt builder
     * @param cognitiveEventBus event bus
     */
    public PromptBuilderStage(PromptBuilder promptBuilder, CognitiveEventBus cognitiveEventBus) {
        this.promptBuilder = promptBuilder;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "PromptBuilderStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_STARTED, "BUILDING", "Building prompt", null, Map.of(
                "documentsUsed", context.knowledgeContext().sourceCount(),
                "memoriesUsed", context.memoryContext().memoryCount()
        ));
        Instant startedAt = Instant.now();
        String prompt = promptBuilder.buildPrompt(context.request(), context.knowledgeContext(), context.memoryContext());
        long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
        boolean containsMemory = prompt.contains("COGNITIVE MEMORY");
        if (containsMemory) {
            cognitiveEventBus.publish(CognitiveEventType.PROMPT_MEMORY_INJECTED, "INJECTED", "Prompt contains memory section", null, Map.of(
                    "memories", context.memoryContext().memoryCount(),
                    "memoryCharacters", context.memoryContext().totalCharacters()
            ));
        }
        LOGGER.info("""
                [JARVIS]
                PROMPT PREVIEW BEFORE MODEL

                SYSTEM
                {}

                MEMORY
                {}

                KNOWLEDGE
                {}

                USER
                {}
                """,
                preview(prompt, "AI identity:", "COGNITIVE MEMORY"),
                containsMemory ? preview(prompt, "COGNITIVE MEMORY", "KNOWLEDGE BASE") : "None",
                prompt.contains("KNOWLEDGE BASE") ? preview(prompt, "KNOWLEDGE BASE", "User message:") : "None",
                preview(prompt, "User message:", null));
        cognitiveEventBus.publish(CognitiveEventType.PROMPT_BUILD_FINISHED, "FINISHED", "Prompt built", null, Map.of(
                "promptBuildTimeMs", durationMs,
                "promptCharacters", prompt.length(),
                "estimatedPromptTokens", prompt.length() / 4
        ));
        return context.withPrompt(prompt)
                .withMetadata("promptBuildTimeMs", durationMs)
                .withMetadata("estimatedPromptTokens", prompt.length() / 4);
    }

    private String preview(String prompt, String startMarker, String endMarker) {
        int start = prompt.indexOf(startMarker);
        if (start < 0) {
            return "";
        }
        int end = endMarker == null ? prompt.length() : prompt.indexOf(endMarker, start + startMarker.length());
        if (end < 0) {
            end = prompt.length();
        }
        String section = prompt.substring(start, end)
                .replaceAll("\\s+", " ")
                .strip();
        if (section.length() <= 300) {
            return section;
        }
        return section.substring(0, 300) + "...";
    }
}
