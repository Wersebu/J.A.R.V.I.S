package com.jarvis.memory.pipeline;

import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.conversation.ConversationContextReducer;
import com.jarvis.memory.conversation.ConversationHistoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Loads short-term conversation context for dialogue continuity.
 */
@Service
@Order(35)
public class ConversationContextStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConversationContextStage.class);

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.jarvis.common.ai.InferenceContextPolicy budgets;
    private final ConversationMemoryService memoryService;
    private final ConversationContextReducer contextReducer;
    private final ConversationHistoryProperties properties;
    private final CognitiveEventBus cognitiveEventBus;

    /**
     * Creates the conversation context stage.
     *
     * @param memoryService conversation memory service
     * @param contextReducer prompt context reducer
     * @param properties conversation history properties
     * @param cognitiveEventBus event bus
     */
    public ConversationContextStage(
            ConversationMemoryService memoryService,
            ConversationContextReducer contextReducer,
            ConversationHistoryProperties properties,
            CognitiveEventBus cognitiveEventBus
    ) {
        this.memoryService = memoryService;
        this.contextReducer = contextReducer;
        this.properties = properties;
        this.cognitiveEventBus = cognitiveEventBus;
    }

    @Override
    public String name() {
        return "ConversationContextStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        if (!properties.enabled()) {
            return context.withConversation(List.of())
                    .withMetadata("conversationHistoryEnabled", false);
        }
        Instant startedAt = Instant.now();
        cognitiveEventBus.publish(CognitiveEventType.CONVERSATION_CONTEXT_LOAD_STARTED,
                "STARTED", "Loading conversation context", null, Map.of(
                        "conversationId", context.conversationId(),
                        "requestId", context.requestId()
                ));
        try {
            ConversationMessage userMessage = ConversationMessage.chat(
                    context.conversationId(),
                    context.requestId(),
                    MessageRole.USER,
                    context.request().message(),
                    Instant.now()
            );
            memoryService.addMessage(context.conversationId(), userMessage);
            cognitiveEventBus.publish(CognitiveEventType.CONVERSATION_MESSAGE_PERSISTED,
                    "PERSISTED", "User message persisted", null, Map.of(
                            "conversationId", context.conversationId(),
                            "requestId", context.requestId(),
                            "messageId", userMessage.id().toString(),
                            "role", userMessage.role().name()
                    ));

            List<ConversationMessage> stored = memoryService.getMessages(context.conversationId());
            List<ConversationMessage> selected = fitHistory(contextReducer.reduce(stored, context.requestId()),context);

            int characters = selected.stream().mapToInt(message -> message.content().length()).sum();
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            CognitiveEventType loadedEvent = selected.isEmpty()
                    ? CognitiveEventType.CONVERSATION_CONTEXT_EMPTY
                    : CognitiveEventType.CONVERSATION_CONTEXT_LOADED;
            cognitiveEventBus.publish(loadedEvent, selected.isEmpty() ? "EMPTY" : "LOADED",
                    selected.isEmpty() ? "Conversation context empty" : "Conversation context loaded",
                    null,
                    Map.of(
                            "conversationId", context.conversationId(),
                            "requestId", context.requestId(),
                            "messagesLoaded", selected.size(),
                            "characters", characters,
                            "durationMs", durationMs
                    ));
            LOGGER.info("[JARVIS][CONVERSATION] USER_MESSAGE_PERSISTED conversationId={} requestId={} messageId={}",
                    context.conversationId(), context.requestId(), userMessage.id());
            LOGGER.info("[JARVIS][CONVERSATION_CONTEXT] LOAD_FINISHED conversationId={} requestId={} messages={} characters={} durationMs={}",
                    context.conversationId(), context.requestId(), selected.size(), characters, durationMs);
            return context.withConversation(selected)
                    .withMetadata("conversationHistoryEnabled", true)
                    .withMetadata("conversationMessagesLoaded", selected.size())
                    .withMetadata("conversationCharactersLoaded", characters)
                    .withMetadata("conversationContextLoadTimeMs", durationMs);
        } catch (RuntimeException exception) {
            long durationMs = Duration.between(startedAt, Instant.now()).toMillis();
            cognitiveEventBus.publish(CognitiveEventType.CONVERSATION_CONTEXT_ERROR,
                    "ERROR", "Conversation context could not be loaded", null, Map.of(
                            "conversationId", context.conversationId(),
                            "requestId", context.requestId(),
                            "durationMs", durationMs,
                            "error", exception.getMessage() == null ? "" : exception.getMessage()
                    ));
            LOGGER.warn("[JARVIS][CONVERSATION_CONTEXT] ERROR conversationId={} requestId={} durationMs={}",
                    context.conversationId(), context.requestId(), durationMs, exception);
            return context.withConversation(List.of())
                    .withMetadata("conversationContextError", exception.getMessage() == null ? "" : exception.getMessage());
        }
    }
    private List<ConversationMessage> fitHistory(List<ConversationMessage> selected,PipelineContext context){
        var selection=com.jarvis.common.ai.InferenceSelectionContext.current();if(budgets==null||selection==null)return selected;
        var brain=new com.jarvis.common.ai.Brain(com.jarvis.common.ai.BrainType.FAST,selection.engine(),selection.model(),"History budget","",0,com.jarvis.common.ai.ReasoningLevel.LOW,selection.configurationId());
        int budget=Math.max(128,budgets.inputBudget(brain,com.jarvis.common.ai.AIJobType.MAIN_MODEL)/3);
        if(selected.stream().mapToInt(m->com.jarvis.common.ai.ModelContextBudget.tokens(m.content())+32).sum()<=budget)return selected;
        var retained=new java.util.ArrayList<ConversationMessage>();int remaining=budget;
        for(int i=selected.size()-1;i>=0&&remaining>64;i--){var m=selected.get(i);String text=m.content();int allowance=Math.min(remaining-32,Math.max(128,budget/4));
            if(com.jarvis.common.ai.ModelContextBudget.tokens(text)>allowance){int chars=Math.max(16,allowance*2);int half=Math.min(text.length()/2,chars/2);text="[Partial historical reference; full message remains stored]\n"+text.substring(0,half)+"\n[...]\n"+text.substring(text.length()-half);}
            int cost=com.jarvis.common.ai.ModelContextBudget.tokens(text)+32;if(cost>remaining)continue;
            retained.add(0,new ConversationMessage(m.id(),m.conversationId(),m.requestId(),m.role(),text,m.createdAt(),m.sequenceNumber(),m.messageType(),m.status()));remaining-=cost;
        }
        return List.copyOf(retained);
    }
}
