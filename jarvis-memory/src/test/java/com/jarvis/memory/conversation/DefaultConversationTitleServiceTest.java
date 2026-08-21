package com.jarvis.memory.conversation;

import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultConversationTitleServiceTest {

    private static final Brain BRAIN = new Brain(BrainType.FAST, "fake", "fake-model", "Fake");

    private FakeConversationRepository conversations;
    private FakeConversationMessageRepository messages;
    private FakeAiProvider provider;
    private FakeEventBus eventBus;
    private DefaultConversationTitleService service;

    @BeforeEach
    void setUp() {
        conversations = new FakeConversationRepository();
        messages = new FakeConversationMessageRepository();
        provider = new FakeAiProvider();
        eventBus = new FakeEventBus();
        service = new DefaultConversationTitleService(conversations, messages, List.of(provider), eventBus);
    }

    @Test
    void generatesTitleAfterFirstCompleteExchange() {
        conversations.createIfAbsent("c1", Instant.parse("2026-08-19T10:00:00Z"));
        messages.append("c1", user("Jak zrobic migracje SQLite dla rozmow?"));
        messages.append("c1", assistant("Dodaj ALTER TABLE z kolumna title_source."));
        provider.response = "\"Migracja SQLite rozmow.\"";

        service.maybeGenerateTitle("c1", BRAIN);

        ConversationRecord record = conversations.find("c1").orElseThrow();
        assertThat(record.title()).isEqualTo("Migracja SQLite rozmow");
        assertThat(record.titleSource()).isEqualTo("GENERATED");
        assertThat(provider.jobType).isEqualTo(AIJobType.BACKGROUND);
        assertThat(eventBus.events).extracting(CognitiveEvent::event)
                .containsExactly(
                        CognitiveEventType.CONVERSATION_TITLE_UPDATED,
                        CognitiveEventType.CONVERSATION_UPDATED
                );
    }

    @Test
    void waitsForAssistantMessage() {
        conversations.createIfAbsent("c1", Instant.parse("2026-08-19T10:00:00Z"));
        messages.append("c1", user("Nazwij rozmowe dopiero po odpowiedzi"));

        service.maybeGenerateTitle("c1", BRAIN);

        assertThat(conversations.find("c1").orElseThrow().titleSource()).isEqualTo("DEFAULT");
        assertThat(provider.calls).isZero();
        assertThat(eventBus.events).isEmpty();
    }

    @Test
    void doesNotRegenerateGeneratedTitle() {
        conversations.records.put("c1", new ConversationRecord("c1", "Pierwszy tytul",
                Instant.now(), Instant.now(), false, "", "GENERATED", "", 0L));
        messages.append("c1", user("Inna tresc"));
        messages.append("c1", assistant("Inna odpowiedz"));

        service.maybeGenerateTitle("c1", BRAIN);

        assertThat(conversations.find("c1").orElseThrow().title()).isEqualTo("Pierwszy tytul");
        assertThat(provider.calls).isZero();
    }

    @Test
    void doesNotOverwriteManualTitle() {
        conversations.records.put("c1", new ConversationRecord("c1", "Moja nazwa",
                Instant.now(), Instant.now(), false, "", "USER", "", 0L));
        messages.append("c1", user("Prosba"));
        messages.append("c1", assistant("Odpowiedz"));

        service.maybeGenerateTitle("c1", BRAIN);

        assertThat(conversations.find("c1").orElseThrow().title()).isEqualTo("Moja nazwa");
        assertThat(provider.calls).isZero();
    }

    @Test
    void lateModelResultDoesNotOverwriteManualRename() {
        conversations.createIfAbsent("c1", Instant.now());
        messages.append("c1", user("Omow raport regresji"));
        messages.append("c1", assistant("Raport obejmuje testy."));
        provider.response = "Raport regresji";
        provider.beforeReturn = () -> conversations.rename("c1", "Recznie ustawione");

        service.maybeGenerateTitle("c1", BRAIN);

        ConversationRecord record = conversations.find("c1").orElseThrow();
        assertThat(record.title()).isEqualTo("Recznie ustawione");
        assertThat(record.titleSource()).isEqualTo("USER");
        assertThat(eventBus.events).isEmpty();
    }

    @Test
    void fallsBackWhenModelFails() {
        conversations.createIfAbsent("c1", Instant.now());
        messages.append("c1", user("Przygotuj plan naprawy regresji w module pamieci"));
        messages.append("c1", assistant("Jasne."));
        provider.failure = new IllegalStateException("offline");

        service.maybeGenerateTitle("c1", BRAIN);

        assertThat(conversations.find("c1").orElseThrow().title())
                .isEqualTo("Przygotuj plan naprawy regresji w module pamieci");
        assertThat(conversations.find("c1").orElseThrow().titleSource()).isEqualTo("GENERATED");
    }

    @Test
    void fallsBackWhenModelReturnsInvalidTitle() {
        conversations.createIfAbsent("c1", Instant.now());
        messages.append("c1", user("Zbierz wymagania dla panelu rozmow"));
        messages.append("c1", assistant("Oto wymagania."));
        provider.response = "Pierwsza linia\nDruga linia";

        service.maybeGenerateTitle("c1", BRAIN);

        assertThat(conversations.find("c1").orElseThrow().title())
                .isEqualTo("Zbierz wymagania dla panelu rozmow");
    }

    @Test
    void fallbackKeepsUnicodeCodePointsIntact() {
        String longText = "🙂".repeat(70) + " koniec";

        String title = service.fallbackTitle(longText);

        assertThat(title.codePointCount(0, title.length())).isEqualTo(60);
        assertThat(title).doesNotContain("\uFFFD");
    }

    private ConversationMessage user(String content) {
        return ConversationMessage.chat("c1", "r1", MessageRole.USER, content, Instant.now());
    }

    private ConversationMessage assistant(String content) {
        return ConversationMessage.chat("c1", "r1", MessageRole.ASSISTANT, content, Instant.now());
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private final Map<String, ConversationRecord> records = new LinkedHashMap<>();

        @Override
        public ConversationRecord createIfAbsent(String conversationId, Instant now) {
            return records.computeIfAbsent(conversationId,
                    id -> new ConversationRecord(id, ConversationRecord.DEFAULT_TITLE, now, now, false, "", "", 0L));
        }

        @Override
        public Optional<ConversationRecord> find(String conversationId) {
            return Optional.ofNullable(records.get(conversationId));
        }

        @Override
        public List<ConversationRecord> list() {
            return List.copyOf(records.values());
        }

        @Override
        public void touch(String conversationId, Instant now) {
        }

        @Override
        public void rename(String conversationId, String title) {
            ConversationRecord existing = records.get(conversationId);
            records.put(conversationId, new ConversationRecord(existing.id(), title, existing.createdAt(),
                    existing.updatedAt(), existing.archived(), existing.lastModel(), "USER",
                    existing.rollingSummary(), existing.summaryUntilSequence()));
        }

        @Override
        public boolean updateGeneratedTitleIfDefault(String conversationId, String title) {
            ConversationRecord existing = records.get(conversationId);
            if (existing == null || !"DEFAULT".equalsIgnoreCase(existing.titleSource())) {
                return false;
            }
            records.put(conversationId, new ConversationRecord(existing.id(), title, existing.createdAt(),
                    existing.updatedAt(), existing.archived(), existing.lastModel(), "GENERATED",
                    existing.rollingSummary(), existing.summaryUntilSequence()));
            return true;
        }

        @Override
        public void setArchived(String conversationId, boolean archived) {
        }

        @Override
        public void updateLastModel(String conversationId, String model) {
        }

        @Override
        public void updateRollingSummary(String conversationId, String summary, long coveredUntilSequence) {
        }

        @Override
        public int delete(String conversationId) {
            return records.remove(conversationId) == null ? 0 : 1;
        }
    }

    private static final class FakeConversationMessageRepository implements ConversationMessageRepository {
        private final Map<String, List<ConversationMessage>> records = new LinkedHashMap<>();

        @Override
        public void append(String conversationId, ConversationMessage message) {
            records.computeIfAbsent(conversationId, id -> new ArrayList<>()).add(message);
        }

        @Override
        public List<ConversationMessage> getAllMessages(String conversationId) {
            return List.copyOf(records.getOrDefault(conversationId, List.of()));
        }

        @Override
        public int countMessages(String conversationId) {
            return records.getOrDefault(conversationId, List.of()).size();
        }

        @Override
        public int deleteAll(String conversationId) {
            return 0;
        }
    }

    private static final class FakeAiProvider implements AIProvider {
        private String response = "Tytul modelu";
        private RuntimeException failure;
        private Runnable beforeReturn = () -> {
        };
        private int calls;
        private AIJobType jobType;

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return chat(brain, prompt, AIJobType.CHAT);
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
            calls++;
            this.jobType = jobType;
            if (failure != null) {
                throw failure;
            }
            beforeReturn.run();
            return new ChatResponse(response);
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }
    }

    private static final class FakeEventBus implements CognitiveEventBus {
        private final List<CognitiveEvent> events = new ArrayList<>();

        @Override
        public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId,
                            Map<String, Object> metadata) {
            events.add(new CognitiveEvent("", String.valueOf(metadata.getOrDefault("conversationId", "")),
                    Instant.now(), event, status, message, null, null, nodeId, metadata));
        }
    }
}
