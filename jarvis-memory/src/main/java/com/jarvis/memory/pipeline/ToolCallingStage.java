package com.jarvis.memory.pipeline;

import com.jarvis.common.event.GenerationFinishedEvent;
import com.jarvis.common.event.TokenEvent;
import com.jarvis.tools.runtime.ToolCallingRequest;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolCallingRuntime;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

/**
 * Executes native tool-calling before ordinary final model streaming.
 */
@Service
@Order(84)
public class ToolCallingStage implements PipelineStage {

    private final ToolCallingRuntime toolCallingRuntime;

    /**
     * Creates the tool-calling stage.
     *
     * @param toolCallingRuntime native tool-calling runtime
     */
    public ToolCallingStage(ToolCallingRuntime toolCallingRuntime) {
        this.toolCallingRuntime = toolCallingRuntime;
    }

    @Override
    public String name() {
        return "ToolCallingStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.response() != null && !context.response().isBlank()) {
            return context;
        }
        ToolCallingResult result = toolCallingRuntime.execute(new ToolCallingRequest(
                context.requestId(),
                context.conversationId(),
                context.request().message(),
                context.prompt(),
                context.brain(),
                context.effectiveKnowledgeMode()
        ));
        if (!result.handled()) {
            return context;
        }
        String answer = result.finalAnswer() == null || result.finalAnswer().isBlank()
                ? "Zakonczylem prace z narzedziami."
                : result.finalAnswer();
        context.modelEventSink().publish(TokenEvent.create(context.conversationId(), answer));
        GenerationFinishedEvent finished = GenerationFinishedEvent.create(
                context.conversationId(),
                0,
                context.brain().type(),
                context.model(),
                null,
                null,
                null
        );
        context.modelEventSink().publish(finished);
        return context.withResponse(answer, finished)
                .withMetadata("toolCallingHandled", true)
                .withMetadata("toolCallingSteps", result.steps().size());
    }
}
