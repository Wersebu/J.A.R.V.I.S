package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.mcp.McpServerManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A chat run must survive the WebSocket that started it: a reconnecting client resumes and receives
 * every frame it missed plus the terminal COMPLETED status, instead of the request being lost.
 */
class ChatRunResumeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void reconnectedSessionReceivesMissedFramesAndCompletion() throws Exception {
        CountDownLatch firstEventSent = new CountDownLatch(1);
        CountDownLatch disconnected = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        ChatService chatService = new ChatService() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return new ChatResponse("");
            }

            @Override
            public void stream(ChatRequest request, Consumer<CognitiveEvent> sink) {
                sink.accept(event(request.conversationId(), "first"));
                firstEventSent.countDown();
                await(disconnected);
                sink.accept(event(request.conversationId(), "second"));
                sink.accept(event(request.conversationId(), "third"));
            }
        };
        ChatRunRegistry registry = new ChatRunRegistry(objectMapper, Duration.ZERO, 1_000, Duration.ofMinutes(1));
        JarvisWebSocketHandler handler = new JarvisWebSocketHandler(chatService, objectMapper,
                new WebSocketWindowsMcpBridgeGateway(objectMapper), mock(McpServerManager.class), registry);

        TestWebSocketSession first = new TestWebSocketSession("first");
        handler.handleTextMessage(first, new TextMessage(objectMapper.writeValueAsString(new ChatRequest("conv-1", "hi"))));
        assertThat(firstEventSent.await(2, TimeUnit.SECONDS)).isTrue();
        first.close();
        handler.afterConnectionClosed(first, CloseStatus.GOING_AWAY);
        disconnected.countDown();

        TestWebSocketSession second = new TestWebSocketSession("second");
        second.onSend(message -> {
            if (String.valueOf(message.getPayload()).contains("\"COMPLETED\"")) {
                finished.countDown();
            }
        });
        // Wait until the run has finished in the background, then resume after seq 1.
        long deadline = System.currentTimeMillis() + 3_000;
        while (registry.runningCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
        handler.handleTextMessage(second, new TextMessage("{\"type\":\"CHAT_RESUME\",\"conversationId\":\"conv-1\",\"afterSeq\":1}"));
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();

        List<JsonNode> frames = frames(second.sentMessages());
        assertThat(frames.get(0).path("type").asText()).isEqualTo("CHAT_RESUMED");
        assertThat(frames.stream().map(node -> node.path("message").asText()).toList())
                .containsSubsequence("second", "third", "Request completed")
                .doesNotContain("first");
        assertThat(frames.get(frames.size() - 1).path("seq").asLong()).isEqualTo(4L);
    }

    @Test
    void unknownConversationIsReportedSoTheClientCanFallBack() throws Exception {
        JarvisWebSocketHandler handler = new JarvisWebSocketHandler(mock(ChatService.class), objectMapper,
                new WebSocketWindowsMcpBridgeGateway(objectMapper), mock(McpServerManager.class),
                new ChatRunRegistry(objectMapper, Duration.ZERO, 1_000, Duration.ofMinutes(1)));
        TestWebSocketSession session = new TestWebSocketSession("s");
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"CHAT_RESUME\",\"conversationId\":\"missing\"}"));
        assertThat(frames(session.sentMessages()).get(0).path("type").asText()).isEqualTo("CHAT_RESUME_UNKNOWN");
    }

    @Test
    void heartbeatIsSentOnlyWhileRunIsRunning() {
        ChatRunRegistry registry = new ChatRunRegistry(objectMapper, Duration.ZERO, 1_000, Duration.ofMinutes(1));
        TestWebSocketSession session = new TestWebSocketSession("s");
        ChatRunRegistry.ChatRun run = registry.start("u", "c", session);
        registry.tick();
        run.finish(Map.of("type", "COMPLETED"));
        registry.tick();
        List<JsonNode> frames = frames(session.sentMessages());
        assertThat(frames.stream().filter(node -> "HEARTBEAT".equals(node.path("type").asText())).count()).isEqualTo(1);
    }

    private List<JsonNode> frames(List<WebSocketMessage<?>> messages) {
        return messages.stream().map(message -> {
            try {
                return objectMapper.readTree(String.valueOf(message.getPayload()));
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }).toList();
    }

    private static CognitiveEvent event(String conversationId, String message) {
        return new CognitiveEvent("r", conversationId, Instant.now(), CognitiveEventType.values()[0], "OK", message,
                null, null, null, Map.of());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
