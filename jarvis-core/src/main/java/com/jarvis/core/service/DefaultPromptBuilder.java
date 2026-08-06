package com.jarvis.core.service;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.prompt.GroundingSource;
import com.jarvis.common.prompt.GroundingSourceType;
import com.jarvis.common.prompt.PromptBuilder;
import com.jarvis.common.prompt.PromptContext;
import com.jarvis.common.prompt.PromptDebugResult;
import com.jarvis.common.prompt.ResponseMode;
import com.jarvis.common.prompt.SystemPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Builds prompts using Jarvis identity loaded from a markdown file.
 */
@Service
public class DefaultPromptBuilder implements PromptBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultPromptBuilder.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final SystemPromptService systemPromptService;
    private final CognitiveEventBus cognitiveEventBus;
    private final Clock clock;

    /**
     * Creates the default prompt builder.
     *
     * @param systemPromptService system prompt service
     * @param cognitiveEventBus cognitive event bus
     */
    public DefaultPromptBuilder(
            SystemPromptService systemPromptService,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.systemPromptService = systemPromptService;
        this.cognitiveEventBus = cognitiveEventBus;
        this.clock = Clock.systemDefaultZone();
    }

    /**
     * Builds the provider prompt with identity, current date, current time, and user message.
     *
     * @param request user chat request
     * @return composed prompt
     */
    @Override
    public String buildPrompt(ChatRequest request, KnowledgeContext knowledgeContext) {
        return buildPrompt(request, knowledgeContext, CognitiveMemoryContext.empty());
    }

    @Override
    public String buildPrompt(ChatRequest request, KnowledgeContext knowledgeContext, CognitiveMemoryContext memoryContext) {
        return buildPrompt(request, knowledgeContext, memoryContext, PromptContext.empty());
    }

    @Override
    public String buildPrompt(
            ChatRequest request,
            KnowledgeContext knowledgeContext,
            CognitiveMemoryContext memoryContext,
            PromptContext promptContext
    ) {
        KnowledgeContext context = knowledgeContext == null ? KnowledgeContext.empty() : knowledgeContext;
        CognitiveMemoryContext memory = memoryContext == null ? CognitiveMemoryContext.empty() : memoryContext;
        PromptContext sources = promptContext == null ? PromptContext.empty() : promptContext;
        String systemPrompt = systemPrompt();
        String groundingPolicy = groundingPolicy(sources);
        String sourceManifest = sourceManifest(sources);
        String conversationPrompt = conversationBlock(sources);
        String knowledge = knowledgeBlock(context);
        String userPrompt = userPrompt(request);
        String finalPrompt = systemPrompt + groundingPolicy + sourceManifest + conversationPrompt + knowledge + userPrompt;
        long conversationMessages = countSources(sources, GroundingSourceType.CONVERSATION);
        LOGGER.info("[JARVIS] Prompt size={} conversationContextItems={} knowledgeSources={} charactersInjected={} estimatedTokens={}",
                finalPrompt.length(),
                conversationMessages,
                context.sourceCount(),
                context.totalCharacters(),
                finalPrompt.length() / 4);
        LOGGER.info("[JARVIS][PROMPT_BUILDER] conversationMessagesInjected={} knowledgeDocumentsInjected={} currentMessageDuplicated=false semanticMemoryInjected=false backgroundMemoryAgentEnabled=false",
                conversationMessages,
                context.sourceCount());
        return finalPrompt;
    }

    /**
     * Builds the debug prompt view with system, knowledge, user, and final prompt sections.
     *
     * @param request user chat request
     * @param knowledgeContext knowledge context
     * @return prompt debug result
     */
    @Override
    public PromptDebugResult buildDebugPrompt(ChatRequest request, KnowledgeContext knowledgeContext) {
        KnowledgeContext context = knowledgeContext == null ? KnowledgeContext.empty() : knowledgeContext;
        String systemPrompt = systemPrompt();
        String knowledge = knowledgeBlock(context);
        String userPrompt = userPrompt(request);
        String finalPrompt = systemPrompt + knowledge + userPrompt;
        LOGGER.info("[JARVIS] Prompt size={} knowledgeSources={} charactersInjected={} estimatedTokens={}",
                finalPrompt.length(),
                context.sourceCount(),
                context.totalCharacters(),
                context.estimatedTokens());
        return new PromptDebugResult(systemPrompt, knowledge, userPrompt, finalPrompt);
    }

    private String conversationBlock(PromptContext promptContext) {
        var conversation = promptContext.groundingSources().stream()
                .filter(source -> source.type() == GroundingSourceType.CONVERSATION)
                .toList();
        if (conversation.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (GroundingSource source : conversation) {
            builder.append(source.title())
                    .append(":\n")
                    .append(source.contentPreview())
                    .append("\n\n");
        }
        return """
                === CONVERSATION CONTEXT ===

                The following messages are recent working conversation context.
                This is not durable long-term memory.

                Use it only for continuity inside the current conversation.

                ----------------------------------------

                %s
                === END CONVERSATION CONTEXT ===

                """.formatted(builder);
    }

    private String groundingPolicy(PromptContext promptContext) {
        if (promptContext.responseMode() != ResponseMode.GROUNDED_PERSONAL) {
            return "";
        }
        return """
                PERSONAL DATA GROUNDING POLICY

                When answering questions about Damian, his hardware, devices, projects,
                preferences, work, history or personal environment:

                1. Use only facts explicitly present in the supplied KNOWLEDGE,
                   CONVERSATION, TOOL RESULTS or current USER MESSAGE.
                2. Never guess missing specifications.
                3. Never provide example specifications as if they belong to Damian.
                4. If a requested fact is unavailable, clearly say:
                   "Nie mam zapisanej informacji o ..."
                5. You may explain how the user can check missing information, but clearly
                   separate instructions from known personal facts.
                6. Never claim that information came from memory, knowledge or tools unless
                   that source is actually supplied below.
                7. If only one component is known, mention only that component.
                   Do not infer the rest of the system.
                8. Prefer uncertainty over invention.
                9. The Knowledge Workspace is the only authoritative long-term memory.
                   Do not rely on legacy SQLite semantic memory.
                   When asked to remember information permanently, use KnowledgeTool.

                Response mode: GROUNDED_PERSONAL
                Personal topic: %s
                Personal confidence: %.2f

                Thinking guidance:
                Identify the requested personal fact, inspect the supplied sources, separate
                known facts from missing facts, answer only with supported facts, and state
                what is unknown.

                """.formatted(
                promptContext.personalQueryAnalysis().personalTopic(),
                promptContext.personalQueryAnalysis().confidence()
        );
    }

    private String sourceManifest(PromptContext promptContext) {
        StringBuilder builder = new StringBuilder();
        builder.append("=== AVAILABLE SOURCES ===\n\n");
        appendSources(builder, "Knowledge sources", promptContext, GroundingSourceType.KNOWLEDGE);
        appendSources(builder, "Tool results", promptContext, GroundingSourceType.TOOL);
        builder.append("=== END SOURCES ===\n\n");
        if (!promptContext.hasMemory()
                && !promptContext.hasKnowledge()
                && !promptContext.hasConversationEvidence()
                && !promptContext.hasToolEvidence()) {
            builder.append("AVAILABLE PERSONAL DATA: NONE\n\n");
        }
        return builder.toString();
    }

    private void appendSources(
            StringBuilder builder,
            String heading,
            PromptContext promptContext,
            GroundingSourceType type
    ) {
        builder.append(heading).append(":\n");
        var matching = promptContext.groundingSources().stream()
                .filter(source -> source.type() == type)
                .toList();
        if (matching.isEmpty()) {
            builder.append("- none\n\n");
            return;
        }
        for (GroundingSource source : matching) {
            builder.append("- [")
                    .append(source.id())
                    .append("] ")
                    .append(source.contentPreview().isBlank() ? source.title() : source.contentPreview())
                    .append("\n");
        }
        builder.append("\n");
    }

    private String systemPrompt() {
        return """
                AI identity:
                %s

                Long-term memory policy:
                The Knowledge Workspace is the only authoritative long-term memory.
                Do not rely on legacy SQLite semantic memory.
                When asked to remember information permanently, use KnowledgeTool.

                Current date: %s
                Current time: %s

                """.formatted(systemPromptService.load(), LocalDate.now(clock), LocalTime.now(clock).format(TIME_FORMATTER));
    }

    private String knowledgeBlock(KnowledgeContext knowledgeContext) {
        if (knowledgeContext.sourceCount() == 0) {
            return "";
        }
        cognitiveEventBus.publish(CognitiveEventType.KNOWLEDGE_INJECTION_STARTED, "INJECTING", "Injecting knowledge into prompt", null, Map.of(
                "sources", knowledgeContext.sourceCount(),
                "charactersInjected", knowledgeContext.totalCharacters(),
                "estimatedTokens", knowledgeContext.estimatedTokens()
        ));
        String knowledge = """
                ========================================

                KNOWLEDGE BASE

                The following information comes from J.A.R.V.I.S. local knowledge library.

                Always prefer this information over your own model knowledge.

                For personal user facts, never use model knowledge to fill missing data.
                For general non-personal questions, you may use your own reasoning when needed.

                ----------------------------------------

                %s

                ========================================

                """.formatted(knowledgeContext.context());
        cognitiveEventBus.publish(CognitiveEventType.KNOWLEDGE_INJECTION_FINISHED, "FINISHED", "Knowledge injection finished", null, Map.of(
                "sources", knowledgeContext.sourceCount(),
                "charactersInjected", knowledgeContext.totalCharacters(),
                "estimatedTokens", knowledgeContext.estimatedTokens()
        ));
        LOGGER.info("[JARVIS] Knowledge injected sources={} characters={} estimatedTokens={}",
                knowledgeContext.sourceCount(),
                knowledgeContext.totalCharacters(),
                knowledgeContext.estimatedTokens());
        return knowledge;
    }

    private String userPrompt(ChatRequest request) {
        return """
                === CURRENT USER MESSAGE ===

                %s

                === END CURRENT USER MESSAGE ===
                """.formatted(request.message());
    }

    private long countSources(PromptContext promptContext, GroundingSourceType type) {
        return promptContext.groundingSources().stream()
                .filter(source -> source.type() == type)
                .count();
    }
}
