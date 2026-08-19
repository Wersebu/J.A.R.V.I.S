package com.jarvis.memory.sqlite;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.memory.cognitive.MemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the durable, never-trimmed conversation message log (ETAP C).
 */
class SQLiteConversationMessageRepositoryTest {

    @TempDir
    Path tempDir;

    private SQLiteConversationMessageRepository repository;

    @BeforeEach
    void setUp() {
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(
                new MemoryProperties(tempDir.resolve("jarvis-memory-test.db").toString(), 3, null, null, null, null));
        new SQLiteMemoryInitializer(connectionFactory).afterPropertiesSet();
        repository = new SQLiteConversationMessageRepository(connectionFactory);
    }

    @Test
    void appendSelfAssignsMonotonicSequenceNumbersPerConversation() {
        repository.append("conversation-1", ConversationMessage.chat("conversation-1", "r1", MessageRole.USER, "hello", Instant.now()));
        repository.append("conversation-1", ConversationMessage.chat("conversation-1", "r1", MessageRole.ASSISTANT, "hi", Instant.now()));

        List<ConversationMessage> messages = repository.getAllMessages("conversation-1");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).sequenceNumber()).isEqualTo(1L);
        assertThat(messages.get(1).sequenceNumber()).isEqualTo(2L);
    }

    @Test
    void getAllMessagesNeverTrimsRegardlessOfWorkingHistoryLength() {
        // MemoryProperties above configured workingHistoryLength=3 - this store must ignore that
        // entirely and retain every message.
        for (int index = 1; index <= 10; index++) {
            repository.append("conversation-1", ConversationMessage.chat("conversation-1", "r" + index, MessageRole.USER, "message " + index, Instant.now()));
        }

        List<ConversationMessage> messages = repository.getAllMessages("conversation-1");
        assertThat(messages).hasSize(10);
        assertThat(messages.get(0).content()).isEqualTo("message 1");
        assertThat(messages.get(9).content()).isEqualTo("message 10");
        assertThat(repository.countMessages("conversation-1")).isEqualTo(10);
    }

    @Test
    void getAllMessagesReturnsDeterministicAscendingOrder() {
        Instant base = Instant.now();
        repository.append("conversation-1", ConversationMessage.chat("conversation-1", "r1", MessageRole.USER, "first", base));
        repository.append("conversation-1", ConversationMessage.chat("conversation-1", "r2", MessageRole.ASSISTANT, "second", base.plusSeconds(1)));
        repository.append("conversation-1", ConversationMessage.chat("conversation-1", "r3", MessageRole.USER, "third", base.plusSeconds(2)));

        List<ConversationMessage> messages = repository.getAllMessages("conversation-1");
        assertThat(messages).extracting(ConversationMessage::content).containsExactly("first", "second", "third");
    }

    @Test
    void reappendingTheSameMessageIdIsIdempotent() {
        ConversationMessage message = ConversationMessage.chat("conversation-1", "r1", MessageRole.USER, "hello", Instant.now());
        repository.append("conversation-1", message);
        repository.append("conversation-1", message);

        assertThat(repository.countMessages("conversation-1")).isEqualTo(1);
    }

    @Test
    void differentConversationsNeverMixMessages() {
        repository.append("conversation-a", ConversationMessage.chat("conversation-a", "r1", MessageRole.USER, "from A", Instant.now()));
        repository.append("conversation-b", ConversationMessage.chat("conversation-b", "r2", MessageRole.USER, "from B", Instant.now()));

        assertThat(repository.getAllMessages("conversation-a")).extracting(ConversationMessage::content).containsExactly("from A");
        assertThat(repository.getAllMessages("conversation-b")).extracting(ConversationMessage::content).containsExactly("from B");
    }

    @Test
    void deleteAllRemovesEveryMessageForOnlyThatConversation() {
        repository.append("conversation-a", ConversationMessage.chat("conversation-a", "r1", MessageRole.USER, "from A", Instant.now()));
        repository.append("conversation-b", ConversationMessage.chat("conversation-b", "r2", MessageRole.USER, "from B", Instant.now()));

        int deleted = repository.deleteAll("conversation-a");

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.getAllMessages("conversation-a")).isEmpty();
        assertThat(repository.getAllMessages("conversation-b")).hasSize(1);
    }
}
