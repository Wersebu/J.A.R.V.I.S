package com.jarvis.memory.pipeline;

import com.jarvis.brain.decision.TaskType;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Selects the effective knowledge access strategy for the request.
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
                : requested == KnowledgeMode.AUTO ? auto(context) : requested;
        cognitiveEventBus.publish(CognitiveEventType.KNOWLEDGE_ANALYZED, "MODE_SELECTED", "Knowledge mode selected", null, Map.of(
                "requestedMode", requested.name(),
                "knowledgeMode", effective.name(),
                "reason", reason(context, effective)
        ));
        return context.withMetadata("knowledgeMode", effective.name());
    }

    private KnowledgeMode auto(PipelineContext context) {
        if (context.executionPlan() == null) {
            return KnowledgeMode.FAST;
        }
        TaskType taskType = context.executionPlan().taskType();
        if (taskType == TaskType.PROGRAMMING
                || taskType == TaskType.CODE_REVIEW
                || taskType == TaskType.ANALYSIS
                || context.executionPlan().estimatedKnowledgeDocuments() >= 3
                || context.executionPlan().complexityScore() >= 7) {
            return KnowledgeMode.RESEARCH;
        }
        return KnowledgeMode.FAST;
    }

    private String reason(PipelineContext context, KnowledgeMode effective) {
        if (hasAttachments(context)) {
            return "Temporary attachments use direct prompt context";
        }
        if (context.request().knowledgeMode() != KnowledgeMode.AUTO) {
            return "User selected " + effective.name();
        }
        return effective == KnowledgeMode.RESEARCH
                ? "Auto selected research for multi-step knowledge work"
                : "Auto selected fast retrieval for low latency";
    }

    private boolean hasAttachments(PipelineContext context) {
        return !context.request().attachments().isEmpty();
    }
}
