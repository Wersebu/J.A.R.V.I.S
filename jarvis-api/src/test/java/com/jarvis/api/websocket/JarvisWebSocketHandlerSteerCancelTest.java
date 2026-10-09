package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.run.ChatRunControl;
import com.jarvis.tools.mcp.McpServerManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Sending a message while Jarvis works (CHAT_STEER) and stopping it (CHAT_CANCEL) over WebSocket.
 */
class JarvisWebSocketHandlerSteerCancelTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void steerIsQueuedForTheRunningAgentAndUnusedMessagesAreHandedBack() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestWebSocketSession session = new TestWebSocketSession("s-steer");
        JarvisWebSocketHandler handler = handler(new BlockingChatService(started, release));

        handler.handleTextMessage(session, new TextMessage(objectMapper.writeValueAsString(new ChatRequest("conv-ws-steer", "zrób stronę"))));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"CHAT_STEER\",\"conversationId\":\"conv-ws-steer\",\"message\":\"dodaj stopkę\"}"));
        release.countDown();

        String all = awaitFrame(session, "\"COMPLETED\"");
        assertThat(all).contains("CHAT_STEER_ACCEPTED").contains("CHAT_STEER_UNCONSUMED").contains("dodaj stopkę");
        assertThat(ChatRunControl.post("conv-ws-steer", "po końcu")).isFalse();
    }

    @Test
    void cancelInterruptsTheRunAndEndsItAsCancelled() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        TestWebSocketSession session = new TestWebSocketSession("s-cancel");
        JarvisWebSocketHandler handler = handler(new BlockingChatService(started, new CountDownLatch(1)));

        handler.handleTextMessage(session, new TextMessage(objectMapper.writeValueAsString(new ChatRequest("conv-ws-cancel", "długie zadanie"))));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"CHAT_CANCEL\",\"conversationId\":\"conv-ws-cancel\"}"));

        String all = awaitFrame(session, "\"CANCELLED\"");
        assertThat(all).contains("CHAT_CANCEL_ACCEPTED").doesNotContain("\"COMPLETED\"");
    }

    private JarvisWebSocketHandler handler(ChatService chatService) {
        return new JarvisWebSocketHandler(chatService, objectMapper, new WebSocketWindowsMcpBridgeGateway(objectMapper),
                mock(McpServerManager.class));
    }

    private static String awaitFrame(TestWebSocketSession session, String fragment) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String all = text(session.sentMessages());
            if (all.contains(fragment)) {
                return all;
            }
            Thread.sleep(20);
        }
        return text(session.sentMessages());
    }

    private static String text(List<WebSocketMessage<?>> messages) {
        StringBuilder builder = new StringBuilder();
        messages.forEach(message -> builder.append(message.getPayload()).append('\n'));
        return builder.toString();
    }

    /** Blocks like a long model call; an interrupt (Stop) ends it the way a real HTTP call would. */
    private static final class BlockingChatService implements ChatService {
        private final CountDownLatch started;
        private final CountDownLatch release;

        private BlockingChatService(CountDownLatch started, CountDownLatch release) {
            this.started = started;
            this.release = release;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return new ChatResponse("");
        }

        @Override
        public void stream(ChatRequest request, Consumer<CognitiveEvent> eventSink) {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", exception);
            }
        }
    }
}
