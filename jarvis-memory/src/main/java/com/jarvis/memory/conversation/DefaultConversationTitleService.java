package com.jarvis.memory.conversation;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-shot, best-effort conversation title generator.
 */
@Service
public class DefaultConversationTitleService implements ConversationTitleService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultConversationTitleService.class);
    private static final int MAX_TITLE_CODE_POINTS = 60;

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final List<AIProvider> aiProviders;
    private final CognitiveEventBus eventBus;
    private final ExecutorService executor;

    public DefaultConversationTitleService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            List<AIProvider> aiProviders,
            CognitiveEventBus eventBus
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.aiProviders = List.copyOf(aiProviders);
        this.eventBus = eventBus;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jarvis-conversation-title");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void maybeGenerateTitleAsync(String conversationId, Brain brain) {
        if (conversationId == null || conversationId.isBlank() || brain == null) {
            return;
        }
        executor.submit(() -> maybeGenerateTitle(conversationId, brain));
    }

    void maybeGenerateTitle(String conversationId, Brain brain) {
        try {
            ConversationRecord record = conversationRepository.find(conversationId).orElse(null);
            if (record == null || !"DEFAULT".equalsIgnoreCase(record.titleSource())) {
                return;
            }
            List<ConversationMessage> messages = messageRepository.getAllMessages(conversationId);
            ConversationMessage firstUser = first(messages, MessageRole.USER);
            ConversationMessage firstAssistant = first(messages, MessageRole.ASSISTANT);
            if (firstUser == null || firstAssistant == null || firstAssistant.content().isBlank()) {
                return;
            }
            String title = generateWithFallback(brain, firstUser.content(), firstAssistant.content());
            if (title.isBlank()) {
                return;
            }
            boolean applied = conversationRepository.updateGeneratedTitleIfDefault(conversationId, title);
            if (applied) {
                eventBus.publish(CognitiveEventType.CONVERSATION_TITLE_UPDATED, "UPDATED",
                        "Conversation title generated", null, Map.of(
                                "conversationId", conversationId,
                                "title", title,
                                "titleSource", "GENERATED"
                        ));
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[CONVERSATION_TITLE] generation failed conversationId={}", conversationId, exception);
        }
    }

    private String generateWithFallback(Brain brain, String user, String assistant) {
        try {
            AIProvider provider = selectProvider(brain);
            String response = provider.chat(brain, prompt(user, assistant), AIJobType.BACKGROUND).response();
            String sanitized = sanitizeModelTitle(response);
            if (!sanitized.isBlank()) {
                return sanitized;
            }
            LOGGER.warn("[CONVERSATION_TITLE] invalid model title, using fallback");
        } catch (RuntimeException exception) {
            LOGGER.warn("[CONVERSATION_TITLE] model call failed, using fallback: {}", exception.getMessage());
        }
        return fallbackTitle(user);
    }

    private AIProvider selectProvider(Brain brain) {
        return aiProviders.stream()
                .filter(provider -> provider.provider().equalsIgnoreCase(brain.provider()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("AI provider is not available: " + brain.provider()));
    }

    private ConversationMessage first(List<ConversationMessage> messages, MessageRole role) {
        return (messages == null ? List.<ConversationMessage>of() : messages).stream()
                .filter(message -> message != null && message.role() == role)
                .findFirst()
                .orElse(null);
    }

    private String prompt(String user, String assistant) {
        return """
                Utworz krotki tytul rozmowy po polsku, jesli rozmowa jest po polsku.
                Maksymalnie 60 znakow. Bez cudzyslowow, bez kropki na koncu, bez prefiksu.
                Tytul ma opisac temat rozmowy, nie odpowiadac uzytkownikowi.
                Zwroc tylko tytul.

                USER:
                %s

                ASSISTANT:
                %s
                """.formatted(limitForPrompt(user), limitForPrompt(assistant));
    }

    private String sanitizeModelTitle(String raw) {
        if (raw == null || raw.contains("\n") || raw.contains("\r")) {
            return "";
        }
        String title = normalize(raw);
        title = stripPrefix(title);
        title = stripQuotes(title);
        while (title.endsWith(".") || title.endsWith(":")) {
            title = title.substring(0, title.length() - 1).strip();
        }
        if (title.isBlank() || title.length() > 90) {
            return "";
        }
        return limitCodePoints(title, MAX_TITLE_CODE_POINTS);
    }

    String fallbackTitle(String user) {
        String title = normalize(user);
        title = stripPrefix(title);
        title = stripQuotes(title);
        return limitCodePoints(title, MAX_TITLE_CODE_POINTS);
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private String stripPrefix(String title) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (lower.startsWith("tytul:") || lower.startsWith("tytuł:") || lower.startsWith("title:")) {
            int colon = title.indexOf(':');
            return title.substring(colon + 1).strip();
        }
        return title;
    }

    private String stripQuotes(String title) {
        String result = title;
        while (result.length() >= 2 && ((result.startsWith("\"") && result.endsWith("\""))
                || (result.startsWith("'") && result.endsWith("'")))) {
            result = result.substring(1, result.length() - 1).strip();
        }
        return result;
    }

    private String limitForPrompt(String text) {
        return limitCodePoints(normalize(text), 600);
    }

    private String limitCodePoints(String text, int limit) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int count = text.codePointCount(0, text.length());
        if (count <= limit) {
            return text;
        }
        int end = text.offsetByCodePoints(0, limit);
        return text.substring(0, end).strip();
    }
}
