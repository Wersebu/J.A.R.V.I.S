package com.jarvis.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.tools.mcp.McpServerManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InteractiveConcurrencyTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private String id() { return UUID.randomUUID().toString(); }
    private TestWebSocketSession session(String owner) {
        var s = new TestWebSocketSession(id()); s.getAttributes().put("jarvis.userId", owner); return s;
    }
    private void send(JarvisWebSocketHandler h, TestWebSocketSession s, String id, String conversation, String message) throws Exception {
        h.handleTextMessage(s, new TextMessage(json.writeValueAsString(Map.of("requestId",id,"conversationId",conversation,"message",message))));
    }
    private String frames(TestWebSocketSession s) { synchronized(s) { return s.sentMessages().stream().map(m -> m.getPayload().toString()).collect(java.util.stream.Collectors.joining("\n")); } }
    private void until(java.util.function.BooleanSupplier condition) throws Exception {
        long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }
    @Test void differentConversationsRunTogetherSameConversationQueuesAndQueuedCancelIsIndependent() throws Exception {
        var release = new CountDownLatch(1); var entered = new CountDownLatch(2);
        var calls = new CopyOnWriteArrayList<String>();
        var service = mock(ChatService.class);
        doAnswer(call -> { ChatRequest r=call.getArgument(0); calls.add(r.message()); entered.countDown(); release.await(4,TimeUnit.SECONDS); return null; })
                .when(service).stream(any(),any());
        try(var registry = new ChatRunRegistry(json,Duration.ZERO,100,Duration.ofMinutes(1))) {
            var h = new JarvisWebSocketHandler(service,json,new WebSocketWindowsMcpBridgeGateway(json),mock(McpServerManager.class),registry);
            var s=session("alice"); String first=id(),second=id(),other=id();
            try {
                send(h,s,first,"A","first"); send(h,s,second,"A","second"); send(h,s,other,"B","other");
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(calls).containsExactlyInAnyOrder("first","other");
                assertThat(frames(s)).contains("CONVERSATION_ORDER");
                h.handleTextMessage(s,new TextMessage(json.writeValueAsString(Map.of("type","CHAT_CANCEL","requestId",second,"conversationId","A"))));
                until(() -> !registry.findRequest("alice",second).orElseThrow().isRunning());
                assertThat(calls).doesNotContain("second");
                assertThat(registry.findRequest("alice",first).orElseThrow().isRunning()).isTrue();
                release.countDown(); until(() -> registry.runningCount()==0);
                var events=s.sentMessages().stream().map(m -> {try{return json.readTree(m.getPayload().toString());}catch(Exception e){throw new RuntimeException(e);}}).toList();
                for(String request : List.of(first,second,other)) {
                    var own=events.stream().filter(e -> request.equals(e.path("requestId").asText())).toList();
                    assertThat(own).isNotEmpty();
                    long previous=0; for(var event:own) { assertThat(event.path("seq").asLong()).isGreaterThan(previous); previous=event.path("seq").asLong(); }
                }
            } finally {release.countDown();}
        }
    }
    @Test void retriesDoNotDuplicateRunsAndOtherOwnerCannotCancelSameConversation() throws Exception {
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var service=mock(ChatService.class);
        doAnswer(call->{entered.countDown();release.await(4,TimeUnit.SECONDS);return null;}).when(service).stream(any(),any());
        try(var registry=new ChatRunRegistry(json,Duration.ZERO,100,Duration.ofMinutes(1))) {
            var h=new JarvisWebSocketHandler(service,json,new WebSocketWindowsMcpBridgeGateway(json),mock(McpServerManager.class),registry);
            var alice=session("alice");var bob=session("bob");String request=id();
            try {
                send(h,alice,request,"same","work");assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();
                send(h,alice,request,"same","work");verify(service,times(1)).stream(any(),any());
                send(h,alice,request,"same","different payload");assertThat(frames(alice)).contains("CHAT_REJECTED");
                h.handleTextMessage(bob,new TextMessage(json.writeValueAsString(Map.of("type","CHAT_CANCEL","requestId",request,"conversationId","same"))));
                assertThat(frames(bob)).contains("CHAT_CANCEL_REJECTED");
                assertThat(registry.findRequest("alice",request).orElseThrow().cancellationRequested()).isFalse();
                release.countDown();until(()->registry.runningCount()==0);
            } finally {release.countDown();}
        }
    }
    @Test void interruptedInferenceThreadDoesNotInterruptSharedSocketWrite() throws Exception {
        try (var registry = new ChatRunRegistry(json,Duration.ZERO,100,Duration.ofMinutes(1))) {
            var session=session("alice");
            session.onSend(message -> assertThat(Thread.currentThread().isInterrupted()).isFalse());
            var run=registry.start("alice","conversation",session);
            Thread.currentThread().interrupt();
            try {
                run.finish(Map.of("type","CANCELLED"));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally { Thread.interrupted(); }
            assertThat(frames(session)).contains("CANCELLED");
        }
    }

    @Test void acceptedCancellationWinsCompletionRaceAndTerminalRunRejectsLateCancel() {
        try (var registry = new ChatRunRegistry(json,Duration.ZERO,100,Duration.ofMinutes(1))) {
            var session=session("alice");var run=registry.start("alice","conversation",session);
            assertThat(run.cancel()).isTrue();
            run.finish(Map.of("type","COMPLETED"));
            assertThat(frames(session)).contains("CANCELLED").doesNotContain("COMPLETED");
            assertThat(run.cancel()).isFalse();
        }
    }

}
