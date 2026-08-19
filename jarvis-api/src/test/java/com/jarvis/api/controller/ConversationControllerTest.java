package com.jarvis.api.controller;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.conversation.ConversationContextReducer;
import com.jarvis.memory.conversation.ConversationHistoryProperties;
import com.jarvis.memory.conversation.ConversationMessageRepository;
import com.jarvis.memory.conversation.ConversationRecord;
import com.jarvis.memory.conversation.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the ETAP D conversations REST API - create/list/get/messages/patch/delete,
 * backed by in-memory fakes of the durable repositories (SQLite persistence itself is already
 * covered by the jarvis-memory repository tests; this pins down the controller's own mapping,
 * partial-update, and not-found semantics).
 */
class ConversationControllerTest {

    private FakeConversationRepository conversationRepository;
    private FakeConversationMessageRepository messageRepository;
    private ConversationController controller;

    @BeforeEach
    void setUp() {
        conversationRepository = new FakeConversationRepository();
        messageRepository = new FakeConversationMessageRepository();
        controller = new ConversationController(
                new NoopConversationMemoryService(),
                (messages, currentRequestId) -> messages,
                new ConversationHistoryProperties(true, 30, 30_000, true, true, false),
                conversationRepository,
                messageRepository
        );
    }

    @Test
    void createPersistsANewConversationWithTheDefaultTitle() {
        ConversationController.ConversationResponse created = controller.create();

        assertThat(created.title()).isEqualTo(ConversationRecord.DEFAULT_TITLE);
        assertThat(conversationRepository.find(created.id())).isPresent();
    }

    @Test
    void listReturnsEveryConversationMostRecentlyUpdatedFirst() {
        Instant base = Instant.now();
        conversationRepository.createIfAbsent("older", base);
        conversationRepository.createIfAbsent("newer", base);
        conversationRepository.touch("older", base.plusSeconds(5));
        conversationRepository.touch("newer", base.plusSeconds(10));

        List<ConversationController.ConversationResponse> list = controller.list();

        assertThat(list).extracting(ConversationController.ConversationResponse::id).containsExactly("newer", "older");
    }

    @Test
    void getReturnsNotFoundForAnUnknownConversation() {
        ResponseEntity<ConversationController.ConversationResponse> response = controller.get("unknown");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void messagesReturnsTheFullDurableHistoryNotABoundedWindow() {
        conversationRepository.createIfAbsent("conversation-1", Instant.now());
        for (int index = 1; index <= 5; index++) {
            messageRepository.append("conversation-1", ConversationMessage.chat(
                    "conversation-1", "r" + index, MessageRole.USER, "message " + index, Instant.now()));
        }

        List<ConversationMessage> messages = controller.messages("conversation-1");

        assertThat(messages).hasSize(5);
    }

    @Test
    void patchRenamesWithoutTouchingArchivedWhenOnlyTitleIsProvided() {
        conversationRepository.createIfAbsent("conversation-1", Instant.now());

        ResponseEntity<ConversationController.ConversationResponse> response = controller.patch(
                "conversation-1", new ConversationController.ConversationPatchRequest("Roblox project folders", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().title()).isEqualTo("Roblox project folders");
        assertThat(response.getBody().archived()).isFalse();
    }

    @Test
    void patchArchivesWithoutTouchingTitleWhenOnlyArchivedIsProvided() {
        conversationRepository.createIfAbsent("conversation-1", Instant.now());
        conversationRepository.rename("conversation-1", "My conversation");

        ResponseEntity<ConversationController.ConversationResponse> response = controller.patch(
                "conversation-1", new ConversationController.ConversationPatchRequest(null, true));

        assertThat(response.getBody().archived()).isTrue();
        assertThat(response.getBody().title()).isEqualTo("My conversation");
    }

    @Test
    void patchReturnsNotFoundForAnUnknownConversation() {
        ResponseEntity<ConversationController.ConversationResponse> response = controller.patch(
                "unknown", new ConversationController.ConversationPatchRequest("New title", null));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteReportsDeletedMessageCount() {
        ConversationController.ConversationResponse created = controller.create();
        ResponseEntity<ConversationController.ConversationDeletedResponse> response = controller.delete(created.id());
        assertThat(response.getBody().conversationId()).isEqualTo(created.id());
    }

    private static final class NoopConversationMemoryService implements ConversationMemoryService {
        @Override
        public void addMessage(String conversationId, ConversationMessage message) {
        }

        @Override
        public List<ConversationMessage> getMessages(String conversationId) {
            return List.of();
        }

        @Override
        public int deleteConversation(String conversationId) {
            return 0;
        }
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
            return records.values().stream()
                    .sorted((a, b) -> b.updatedAt().compareTo(a.updatedAt()))
                    .toList();
        }

        @Override
        public void touch(String conversationId, Instant now) {
            update(conversationId, existing -> new ConversationRecord(existing.id(), existing.title(), existing.createdAt(),
                    now, existing.archived(), existing.lastModel(), existing.rollingSummary(), existing.summaryUntilSequence()));
        }

        @Override
        public void rename(String conversationId, String title) {
            update(conversationId, existing -> new ConversationRecord(existing.id(), title, existing.createdAt(),
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
            update(conversationId, existing -> new ConversationRecord(existing.id(), existing.title(), existing.createdAt(),
                    existing.updatedAt(), archived, existing.lastModel(), existing.rollingSummary(), existing.summaryUntilSequence()));
        }

        @Override
        public void updateLastModel(String conversationId, String model) {
            update(conversationId, existing -> new ConversationRecord(existing.id(), existing.title(), existing.createdAt(),
                    existing.updatedAt(), existing.archived(), model, existing.rollingSummary(), existing.summaryUntilSequence()));
        }

        @Override
        public void updateRollingSummary(String conversationId, String summary, long coveredUntilSequence) {
            update(conversationId, existing -> new ConversationRecord(existing.id(), existing.title(), existing.createdAt(),
                    existing.updatedAt(), existing.archived(), existing.lastModel(), summary, coveredUntilSequence));
        }

        @Override
        public int delete(String conversationId) {
            return records.remove(conversationId) != null ? 1 : 0;
        }

        private void update(String conversationId, java.util.function.UnaryOperator<ConversationRecord> updater) {
            ConversationRecord existing = records.get(conversationId);
            if (existing != null) {
                records.put(conversationId, updater.apply(existing));
            }
        }
    }

    private static final class FakeConversationMessageRepository implements ConversationMessageRepository {
        private final Map<String, List<ConversationMessage>> messages = new LinkedHashMap<>();

        @Override
        public void append(String conversationId, ConversationMessage message) {
            messages.computeIfAbsent(conversationId, key -> new ArrayList<>()).add(message);
        }

        @Override
        public List<ConversationMessage> getAllMessages(String conversationId) {
            return List.copyOf(messages.getOrDefault(conversationId, List.of()));
        }

        @Override
        public int countMessages(String conversationId) {
            return messages.getOrDefault(conversationId, List.of()).size();
        }

        @Override
        public int deleteAll(String conversationId) {
            List<ConversationMessage> removed = messages.remove(conversationId);
            return removed == null ? 0 : removed.size();
        }
    }
}
