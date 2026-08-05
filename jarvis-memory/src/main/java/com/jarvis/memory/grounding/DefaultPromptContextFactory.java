package com.jarvis.memory.grounding;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.memory.CognitiveMemoryContext;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.prompt.GroundingSource;
import com.jarvis.common.prompt.GroundingSourceType;
import com.jarvis.common.prompt.PersonalQueryAnalysis;
import com.jarvis.common.prompt.PersonalQueryDetector;
import com.jarvis.common.prompt.PromptContext;
import com.jarvis.common.prompt.PromptContextFactory;
import com.jarvis.common.prompt.ResponseMode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds source manifests for prompt grounding.
 */
@Service
public class DefaultPromptContextFactory implements PromptContextFactory {

    private static final int PREVIEW_LIMIT = 180;

    private final PersonalQueryDetector personalQueryDetector;

    /**
     * Creates the prompt context factory.
     *
     * @param personalQueryDetector personal query detector
     */
    public DefaultPromptContextFactory(PersonalQueryDetector personalQueryDetector) {
        this.personalQueryDetector = personalQueryDetector;
    }

    @Override
    public PromptContext create(
            String userMessage,
            CognitiveMemoryContext memoryContext,
            KnowledgeContext knowledgeContext,
            List<ConversationMessage> conversation
    ) {
        CognitiveMemoryContext memory = memoryContext == null ? CognitiveMemoryContext.empty() : memoryContext;
        KnowledgeContext knowledge = knowledgeContext == null ? KnowledgeContext.empty() : knowledgeContext;
        List<ConversationMessage> history = conversation == null ? List.of() : List.copyOf(conversation);
        List<GroundingSource> sources = new ArrayList<>();
        memory.memories().forEach(record -> sources.add(new GroundingSource(
                GroundingSourceType.MEMORY,
                record.id().toString(),
                record.title(),
                preview(record.content()),
                record.confidence()
        )));
        knowledge.sources().forEach(source -> sources.add(new GroundingSource(
                GroundingSourceType.KNOWLEDGE,
                source.documentId().toString(),
                source.title(),
                source.relativePath(),
                1.0
        )));
        history.stream()
                .skip(Math.max(0, history.size() - 4))
                .forEach(message -> sources.add(new GroundingSource(
                        GroundingSourceType.CONVERSATION,
                        message.createdAt() == null ? "conversation" : message.createdAt().toString(),
                        message.role().name(),
                        preview(message.content()),
                        1.0
                )));
        if (userMessage != null && !userMessage.isBlank()) {
            sources.add(new GroundingSource(
                    GroundingSourceType.USER_MESSAGE,
                    "current",
                    "Current user message",
                    preview(userMessage),
                    1.0
            ));
        }
        PersonalQueryAnalysis analysis = personalQueryDetector.analyze(userMessage);
        ResponseMode responseMode = analysis.personalQuery() ? ResponseMode.GROUNDED_PERSONAL : ResponseMode.STANDARD;
        return new PromptContext(
                !memory.isEmpty(),
                knowledge.sourceCount() > 0,
                !history.isEmpty(),
                false,
                sources,
                analysis,
                responseMode
        );
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT);
    }
}
