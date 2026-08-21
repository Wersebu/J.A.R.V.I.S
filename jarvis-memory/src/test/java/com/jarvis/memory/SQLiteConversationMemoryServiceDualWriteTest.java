package com.jarvis.memory;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.image.ConversationImageProperties;
import com.jarvis.memory.image.SQLiteConversationImageRegistry;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import com.jarvis.memory.sqlite.SQLiteConversationMessageRepository;
import com.jarvis.memory.sqlite.SQLiteConversationRepository;
import com.jarvis.memory.sqlite.SQLiteMemoryInitializer;
import com.jarvis.memory.sqlite.SQLiteWorkingMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the core ETAP C requirement (CZ. 8): {@code working_memory}'s existing trim-to-N behavior
 * must keep working unchanged for prompt building, while the durable store retains every message
 * regardless of how many messages a conversation accumulates - "full history is never auto-deleted
 * after N messages" going forward from this change.
 */
class SQLiteConversationMemoryServiceDualWriteTest {

    private static final int WORKING_HISTORY_LENGTH = 3;

    private SQLiteConversationMemoryService service;
    private SQLiteConversationMessageRepository durableMessages;
    private SQLiteConversationRepository conversations;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), WORKING_HISTORY_LENGTH, null, null, null, null));
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        SQLiteWorkingMemoryStore workingMemoryStore = new SQLiteWorkingMemoryStore(connectionFactory,
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), WORKING_HISTORY_LENGTH, null, null, null, null));
        conversations = new SQLiteConversationRepository(connectionFactory);
        durableMessages = new SQLiteConversationMessageRepository(connectionFactory);
        SQLiteConversationImageRegistry images = new SQLiteConversationImageRegistry(connectionFactory,
                new ConversationImageProperties(true, null, 0, 0, null));
        service = new SQLiteConversationMemoryService(workingMemoryStore, conversations, durableMessages, images);
    }

    @Test
    void workingMemoryStillTrimsButTheDurableStoreRetainsEverything() {
        for (int index = 1; index <= 7; index++) {
            service.addMessage("conversation-1", ConversationMessage.chat(
                    "conversation-1", "r" + index, MessageRole.USER, "message " + index, Instant.now()));
        }

        // Unchanged existing behavior: the bounded working-memory window still only has the last N.
        List<ConversationMessage> recent = service.getMessages("conversation-1");
        assertThat(recent).hasSize(WORKING_HISTORY_LENGTH);
        assertThat(recent.get(recent.size() - 1).content()).isEqualTo("message 7");

        // New durable behavior: nothing was lost.
        List<ConversationMessage> durable = durableMessages.getAllMessages("conversation-1");
        assertThat(durable).hasSize(7);
        assertThat(durable.get(0).content()).isEqualTo("message 1");
        assertThat(durable.get(6).content()).isEqualTo("message 7");
    }

    @Test
    void addMessageCreatesAndTouchesTheConversationRecord() {
        Instant before = Instant.now();
        service.addMessage("conversation-1", ConversationMessage.chat(
                "conversation-1", "r1", MessageRole.USER, "hello", Instant.now()));

        var record = conversations.find("conversation-1");
        assertThat(record).isPresent();
        assertThat(record.get().title()).isEqualTo(com.jarvis.memory.conversation.ConversationRecord.DEFAULT_TITLE);
        assertThat(record.get().updatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void deleteConversationRemovesBothWorkingMemoryAndDurableData() {
        service.addMessage("conversation-1", ConversationMessage.chat(
                "conversation-1", "r1", MessageRole.USER, "hello", Instant.now()));

        service.deleteConversation("conversation-1");

        assertThat(service.getMessages("conversation-1")).isEmpty();
        assertThat(durableMessages.getAllMessages("conversation-1")).isEmpty();
        assertThat(conversations.find("conversation-1")).isEmpty();
    }

    @Test
    void restartingAgainstTheSameDatabaseFileStillFindsAllDurableMessages() {
        service.addMessage("conversation-1", ConversationMessage.chat(
                "conversation-1", "r1", MessageRole.USER, "hello before restart", Instant.now()));

        // Simulates a Core restart: a brand-new connection factory/initializer/repository set
        // against the exact same database file, no in-memory state carried over.
        SQLiteConnectionFactory restarted = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), WORKING_HISTORY_LENGTH, null, null, null, null));
        new SQLiteMemoryInitializer(restarted).afterPropertiesSet();
        SQLiteConversationMessageRepository afterRestart = new SQLiteConversationMessageRepository(restarted);

        assertThat(afterRestart.getAllMessages("conversation-1")).extracting(ConversationMessage::content)
                .containsExactly("hello before restart");
    }
}
