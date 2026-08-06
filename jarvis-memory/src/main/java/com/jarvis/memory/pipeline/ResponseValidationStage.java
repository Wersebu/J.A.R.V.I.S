package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.AIProviderException;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.prompt.GroundedResponseValidator;
import com.jarvis.common.prompt.GroundedValidationResult;
import com.jarvis.common.prompt.GroundingSourceType;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.job.MemoryJobService;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Performs basic response validation.
 */
@Service
@Order(100)
public class ResponseValidationStage implements PipelineStage {

    private final CognitiveEventBus cognitiveEventBus;
    private final ConversationMemoryService memoryService;
    private final MemoryJobService memoryJobService;
    private final GroundedResponseValidator groundedResponseValidator;
    private final List<AIProvider> aiProviders;

    /**
     * Creates the response validation stage.
     *
     * @param cognitiveEventBus event bus
     * @param memoryService memory service
     * @param memoryJobService background memory job service
     * @param groundedResponseValidator grounded response validator
     * @param aiProviders available AI providers
     */
    public ResponseValidationStage(
            CognitiveEventBus cognitiveEventBus,
            ConversationMemoryService memoryService,
            MemoryJobService memoryJobService,
            GroundedResponseValidator groundedResponseValidator,
            List<AIProvider> aiProviders
    ) {
        this.cognitiveEventBus = cognitiveEventBus;
        this.memoryService = memoryService;
        this.memoryJobService = memoryJobService;
        this.groundedResponseValidator = groundedResponseValidator;
        this.aiProviders = List.copyOf(aiProviders);
    }

    @Override
    public String name() {
        return "ResponseValidationStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (context.response() == null) {
            throw new IllegalStateException("Model response is null");
        }
        if (context.response().isBlank()) {
            throw new IllegalStateException("Model response is empty");
        }
        ValidationOutcome validation = validateAndCorrect(context);
        PipelineContext responseContext = validation.context();
        memoryService.addMessage(
                responseContext.conversationId(),
                new ConversationMessage(MessageRole.ASSISTANT, responseContext.response(), Instant.now())
        );
        publishGrounding(responseContext, validation);
        var finishedEvent = responseContext.generationFinishedEvent();
        cognitiveEventBus.publish(CognitiveEventType.REQUEST_FINISHED, "FINISHED", "Request finished", null, Map.ofEntries(
                Map.entry("generationTimeMs", finishedEvent == null ? 0 : finishedEvent.generationTimeMs()),
                Map.entry("retrievalTimeMs", responseContext.retrievalResult() == null ? 0 : responseContext.retrievalResult().executionTimeMs()),
                Map.entry("contextBuildTimeMs", responseContext.knowledgeContext().buildTimeMs()),
                Map.entry("promptBuildTimeMs", responseContext.metadata().getOrDefault("promptBuildTimeMs", 0L)),
                Map.entry("documentsUsed", responseContext.knowledgeContext().sourceCount()),
                Map.entry("tokensGenerated", finishedEvent == null || finishedEvent.completionTokens() == null ? 0 : finishedEvent.completionTokens()),
                Map.entry("estimatedPromptTokens", responseContext.metadata().getOrDefault("estimatedPromptTokens", 0)),
                Map.entry("taskType", responseContext.executionPlan().taskType().name()),
                Map.entry("complexity", responseContext.executionPlan().complexityScore()),
                Map.entry("reasoningLevel", responseContext.executionPlan().reasoningLevel().name()),
                Map.entry("reason", responseContext.executionPlan().reason()),
                Map.entry("confidence", responseContext.executionPlan().confidence())
        ));
        memoryJobService.submit(responseContext);
        return responseContext.withMetadata("memoryJobSubmitted", true)
                .withMetadata("grounded", validation.finalResult().valid())
                .withMetadata("groundingRegenerated", validation.regenerated());
    }

    private ValidationOutcome validateAndCorrect(PipelineContext context) {
        if (Boolean.TRUE.equals(context.metadata().get("toolCallingHandled"))) {
            return new ValidationOutcome(context, GroundedValidationResult.success(), false);
        }
        GroundedValidationResult first = groundedResponseValidator.validate(context.response(), context.promptContext());
        if (first.valid()) {
            return new ValidationOutcome(context, first, false);
        }
        PipelineContext correctedContext = regenerate(context, first);
        GroundedValidationResult second = groundedResponseValidator.validate(correctedContext.response(), correctedContext.promptContext());
        if (second.valid()) {
            return new ValidationOutcome(correctedContext, second, true);
        }
        return new ValidationOutcome(
                context.withResponse("Nie mam zapisanej wystarczającej informacji, aby odpowiedzieć bez zgadywania.", null),
                second,
                true
        );
    }

    private PipelineContext regenerate(PipelineContext context, GroundedValidationResult validation) {
        String correctionPrompt = """
                The previous answer failed grounded personal data validation.

                Failure reason:
                %s

                Correct the answer using LOW reasoning.
                Use only the sources in the original prompt.
                If the requested personal fact is unavailable, say that no stored information is available.
                Return only the corrected final answer.

                ORIGINAL PROMPT:
                %s

                INVALID ANSWER:
                %s
                """.formatted(validation.reason(), context.prompt(), context.response());
        var correctionBrain = context.brain().withRoutingMetadata("Grounded response correction", 0L, ReasoningLevel.LOW);
        String corrected = selectProvider(context).chat(correctionBrain, correctionPrompt, AIJobType.DEBUG).response();
        return context.withResponse(corrected, context.generationFinishedEvent());
    }

    private AIProvider selectProvider(PipelineContext context) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(context.brain().provider()))
                .findFirst()
                .orElseThrow(() -> new AIProviderException("AI provider is not available: " + context.brain().provider()));
    }

    private void publishGrounding(PipelineContext context, ValidationOutcome validation) {
        cognitiveEventBus.publish(CognitiveEventType.RESPONSE_GROUNDING, validation.finalResult().valid() ? "GROUNDED" : "UNSUPPORTED",
                "Response grounding diagnostics", null, Map.of(
                        "personalQuery", context.promptContext().personalQueryAnalysis().personalQuery(),
                        "grounded", validation.finalResult().valid(),
                        "memorySourcesUsed", countSources(context, GroundingSourceType.MEMORY),
                        "knowledgeSourcesUsed", countSources(context, GroundingSourceType.KNOWLEDGE),
                        "toolSourcesUsed", countSources(context, GroundingSourceType.TOOL),
                        "unsupportedClaimDetected", validation.finalResult().unsupportedClaimDetected(),
                        "regenerated", validation.regenerated(),
                        "reason", validation.finalResult().reason()
                ));
    }

    private long countSources(PipelineContext context, GroundingSourceType type) {
        return context.promptContext().groundingSources().stream()
                .filter(source -> source.type() == type)
                .count();
    }

    private record ValidationOutcome(
            PipelineContext context,
            GroundedValidationResult finalResult,
            boolean regenerated
    ) {
    }
}
