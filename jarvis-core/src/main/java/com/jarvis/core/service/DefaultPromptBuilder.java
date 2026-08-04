package com.jarvis.core.service;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.prompt.PromptBuilder;
import com.jarvis.common.prompt.PromptDebugResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private final Resource identityResource;
    private final CognitiveEventBus cognitiveEventBus;
    private final Clock clock;

    /**
     * Creates the default prompt builder.
     *
     * @param identityResource identity markdown resource
     * @param cognitiveEventBus cognitive event bus
     */
    public DefaultPromptBuilder(
            @Value("${jarvis.ai.identity-file}") Resource identityResource,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.identityResource = identityResource;
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
        return buildDebugPrompt(request, knowledgeContext).finalPrompt();
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

    private String systemPrompt() {
        return """
                AI identity:
                %s

                Current date: %s
                Current time: %s

                """.formatted(loadIdentity(), LocalDate.now(clock), LocalTime.now(clock).format(TIME_FORMATTER));
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

                If the answer cannot be found here, use your own reasoning.

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
                User message:
                %s
                """.formatted(request.message());
    }

    private String loadIdentity() {
        try {
            return identityResource.getContentAsString(StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            LOGGER.error("[JARVIS] Failed to load AI identity from {}", identityResource, exception);
            return "";
        }
    }
}
