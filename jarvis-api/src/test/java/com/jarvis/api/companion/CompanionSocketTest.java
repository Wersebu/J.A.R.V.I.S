package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.*;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.auth.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CompanionSocketTest {
    @Test void handshakeSnapshotReplayRestartAndMalformedCommands() throws Exception {
        ChatService chat=mock(ChatService.class);when(chat.chat(any())).thenReturn(new ChatResponse("done"));
        ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
        try(var tasks=new CompanionTasks(chat,1)){
            CompanionSocket socket=new CompanionSocket(tasks,mapper);
            try{
                WebSocketSession session=mock(WebSocketSession.class);when(session.getId()).thenReturn("s");when(session.isOpen()).thenReturn(true);when(session.getAttributes()).thenReturn(Map.of("jarvis.userId","alice"));
                var frames=new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();doAnswer(i->{frames.add(mapper.readTree(((TextMessage)i.getArgument(0)).getPayload()));return null;}).when(session).sendMessage(any());
                socket.afterConnectionEstablished(session);assertEquals("system.connected",frames.getFirst().path("type").asText());assertEquals(CompanionTasks.version(),frames.getFirst().path("protocolVersion").asText());assertEquals(tasks.serverInstanceId(),frames.getFirst().path("payload").path("serverInstanceId").asText());
                socket.handleTextMessage(session,new TextMessage("{\"type\":\"state.sync\"}"));assertEquals("system.snapshot",frames.getLast().path("type").asText());
                socket.handleTextMessage(session,new TextMessage("{\"type\":\"events.resume\",\"afterSequence\":0,\"serverInstanceId\":\"old-server\"}"));assertEquals("system.reset_required",frames.getLast().path("type").asText());
                socket.handleTextMessage(session,new TextMessage("null"));assertEquals("system.error",frames.getLast().path("type").asText());
                socket.handleTextMessage(session,new TextMessage("{"));assertEquals("system.error",frames.getLast().path("type").asText());
                var task=tasks.submit("alice",new CompanionTasks.Request("hi","TASK",50,5,1));
                socket.handleTextMessage(session,new TextMessage("{\"type\":\"events.resume\",\"afterSequence\":0}"));
                assertTrue(frames.stream().anyMatch(f->f.path("type").asText().equals("task.created")&&f.path("taskId").asText().equals(task.taskId())));
                assertEquals("system.resumed",frames.getLast().path("type").asText());
            }finally{socket.close();}
        }
    }
    @Test void rejectsUnauthenticatedSocketAndControllerContext() throws Exception {
        try(var tasks=new CompanionTasks(mock(ChatService.class),1)){
            var socket=new CompanionSocket(tasks,new ObjectMapper());
            try{WebSocketSession session=mock(WebSocketSession.class);when(session.getAttributes()).thenReturn(Map.of());socket.afterConnectionEstablished(session);verify(session).close(CloseStatus.POLICY_VIOLATION);}finally{socket.close();}
            CurrentUserContext.clear();assertThrows(IllegalStateException.class,()->new CompanionController(tasks).list());
        }
    }
}
