package com.jarvis.tools.runtime;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.knowledge.KnowledgeMode;

import java.util.List;

/**
 * Input for a native tool-calling loop.
 *
 * @param requestId request identifier
 * @param conversationId conversation identifier
 * @param userMessage latest user message
 * @param goal external-capability goal selected by the main model
 * @param reason main model decision reason summary
 * @param basePrompt existing prompt context
 * @param brain selected brain
 * @param knowledgeMode effective knowledge mode
 * @param images current-message images, carried by reference from {@code PipelineContext#images()}
 *         - never copied. Empty when the current message has no images, or when the active model
 *         does not support vision (that gate already ran upstream in {@code ModelExecutionStage}
 *         before a {@code TOOL_REQUEST} could even reach the tool loop).
 */
public record ToolCallingRequest(
        String requestId,
        String conversationId,
        String userMessage,
        String goal,
        String reason,
        String basePrompt,
        Brain brain,
        KnowledgeMode knowledgeMode,
        List<ImageAttachment> images
) {

    /**
     * Creates an immutable request.
     */
    public ToolCallingRequest {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /**
     * Compatibility constructor for call sites that don't (yet) have a goal/reason or images.
     *
     * @param requestId request identifier
     * @param conversationId conversation identifier
     * @param userMessage latest user message
     * @param basePrompt existing prompt context
     * @param brain selected brain
     * @param knowledgeMode effective knowledge mode
     */
    public ToolCallingRequest(
            String requestId,
            String conversationId,
            String userMessage,
            String basePrompt,
            Brain brain,
            KnowledgeMode knowledgeMode
    ) {
        this(requestId, conversationId, userMessage, "", "", basePrompt, brain, knowledgeMode, List.of());
    }

    /**
     * Compatibility constructor for call sites with a goal/reason but no images.
     *
     * @param requestId request identifier
     * @param conversationId conversation identifier
     * @param userMessage latest user message
     * @param goal external-capability goal selected by the main model
     * @param reason main model decision reason summary
     * @param basePrompt existing prompt context
     * @param brain selected brain
     * @param knowledgeMode effective knowledge mode
     */
    public ToolCallingRequest(
            String requestId,
            String conversationId,
            String userMessage,
            String goal,
            String reason,
            String basePrompt,
            Brain brain,
            KnowledgeMode knowledgeMode
    ) {
        this(requestId, conversationId, userMessage, goal, reason, basePrompt, brain, knowledgeMode, List.of());
    }
}
