package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.tools.mcp.McpServerManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for a real production bug: {@code handleTextMessage} used to run {@code
 * chatService.stream(...)} synchronously, so a long-running chat pipeline blocked delivery of
 * every other frame on the same WebSocket session for its entire duration - the Jakarta/Spring
 * WebSocket container never invokes {@code handleTextMessage} again for one session until the
 * current call returns. Since chat and the MCP bridge share one persistent connection, this meant
 * an in-flight {@code MCP_BRIDGE_RESPONSE} the pipeline itself was waiting on could not be
 * delivered until the pipeline finally finished - in one real trace, the Windows bridge answered a
 * tool call in under 5ms, but Core did not see that response for over 2 minutes, exactly matching
 * how long the triggering chat pipeline ran. {@code handleTextMessage} must now return
 * near-instantly regardless of how long the chat pipeline takes.
 */
class JarvisWebSocketHandlerAsyncChatTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void handleTextMessageReturnsBeforeTheChatPipelineFinishes() throws Exception {
        CountDownLatch chatStarted = new CountDownLatch(1);
        CountDownLatch releaseChat = new CountDownLatch(1);
        BlockingChatService chatService = new BlockingChatService(chatStarted, releaseChat);
        JarvisWebSocketHandler handler = new JarvisWebSocketHandler(
                chatService, objectMapper,
                new WebSocketWindowsMcpBridgeGateway(objectMapper),
                mock(McpServerManager.class)
        );
        WebSocketSession session = fakeOpenSession();
        TextMessage message = new TextMessage(objectMapper.writeValueAsString(
                new ChatRequest("conversation-1", "hello")));

        try {
            long startedNanos = System.nanoTime();
            handler.handleTextMessage(session, message);
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

            assertThat(elapsedMs)
                    .as("handleTextMessage must return immediately, not wait for the chat pipeline")
                    .isLessThan(1_000L);
            assertThat(chatStarted.await(2, TimeUnit.SECONDS))
                    .as("the chat pipeline must still actually run, just not block the caller")
                    .isTrue();
        } finally {
            releaseChat.countDown();
        }
    }

    private WebSocketSession fakeOpenSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("session-1");
        return session;
    }

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
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the blocking chat service in time");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
