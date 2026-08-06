package com.jarvis.memory.conversation;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.ConversationMessageStatus;
import com.jarvis.common.memory.ConversationMessageType;
import com.jarvis.common.memory.MessageRole;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic newest-message conversation reducer.
 */
@Service
public class RecentWindowConversationContextReducer implements ConversationContextReducer {

    private final ConversationHistoryProperties properties;

    /**
     * Creates the reducer.
     *
     * @param properties conversation history properties
     */
    public RecentWindowConversationContextReducer(ConversationHistoryProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<ConversationMessage> reduce(List<ConversationMessage> messages, String currentRequestId) {
        if (!properties.enabled() || messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ConversationMessage> selected = new ArrayList<>();
        int characters = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            ConversationMessage message = messages.get(index);
            if (!include(message, currentRequestId)) {
                continue;
            }
            int messageCharacters = message.content().length();
            if (!selected.isEmpty() && characters + messageCharacters > properties.maxCharacters()) {
                break;
            }
            selected.add(message);
            characters += messageCharacters;
            if (selected.size() >= properties.maxMessages()) {
                break;
            }
        }
        return selected.reversed();
    }

    private boolean include(ConversationMessage message, String currentRequestId) {
        if (message == null || message.content().isBlank()) {
            return false;
        }
        if (message.status() != ConversationMessageStatus.FINAL) {
            return false;
        }
        if (message.requestId().equals(currentRequestId) && message.role() == MessageRole.USER) {
            return false;
        }
        if (message.messageType() == ConversationMessageType.TOOL_RESULT) {
            return properties.includeToolResults();
        }
        if (message.messageType() == ConversationMessageType.SYSTEM_EVENT) {
            return properties.includeSystemEvents();
        }
        return message.messageType() == ConversationMessageType.CHAT
                || message.messageType() == ConversationMessageType.TOOL_CALL
                || message.messageType() == ConversationMessageType.DRAFT_APPROVAL;
    }
}
