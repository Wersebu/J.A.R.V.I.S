package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Selects the effective knowledge access strategy for the request.
 *
 * <p>AUTO never escalates to RESEARCH: whether the request needs knowledge/research is a
 * model decision made through native TOOL_REQUEST calls, not a Core-side heuristic. An
 * explicit non-AUTO value supplied by the caller is still honored as-is.
 */
@Service
@Order(55)
public class KnowledgeModeStage implements PipelineStage {

    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the knowledge mode stage.
     *
     * @param cognitiveEventBus cognitive event bus
     */
    public KnowledgeModeStage(CognitiveEventBus cognitiveEventBus) {
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "KnowledgeModeStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        KnowledgeMode requested = context.request().knowledgeMode();
        KnowledgeMode effective = hasAttachments(context)
                ? KnowledgeMode.FAST
                : requested == KnowledgeMode.AUTO ? KnowledgeMode.FAST : requested;
        cognitiveEventBus.publish(CognitiveEventType.KNOWLEDGE_ANALYZED, "MODE_SELECTED", "Knowledge mode selected", null, Map.of(
                "requestedMode", requested.name(),
                "knowledgeMode", effective.name(),
                "reason", reason(context, effective)
        ));
        return context.withMetadata("knowledgeMode", effective.name());
    }

    private String reason(PipelineContext context, KnowledgeMode effective) {
        if (hasAttachments(context)) {
            return "Temporary attachments use direct prompt context";
        }
        if (context.request().knowledgeMode() != KnowledgeMode.AUTO) {
            return "User selected " + effective.name();
        }
        return "Auto defaults to FAST; knowledge/research is a model tool decision, not a Core heuristic";
    }

    private boolean hasAttachments(PipelineContext context) {
        return !context.request().attachments().isEmpty();
    }
}
