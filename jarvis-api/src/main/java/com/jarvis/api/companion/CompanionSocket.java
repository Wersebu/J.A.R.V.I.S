package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Polls the bounded event journal so a slow socket cannot block task execution. */
@Component
public class CompanionSocket extends TextWebSocketHandler {
    private final CompanionTasks tasks; private final ObjectMapper mapper;
    private final Map<String,Link> links=new ConcurrentHashMap<>();
    private final ScheduledExecutorService pump;
    private final ExecutorService delivery=Executors.newVirtualThreadPerTaskExecutor();
    private static final class Link {
        final WebSocketSession socket; final String owner; long cursor; boolean subscribed;
        final java.util.concurrent.atomic.AtomicBoolean draining=new java.util.concurrent.atomic.AtomicBoolean();
        Link(WebSocketSession socket){this.socket=socket; owner=(String)socket.getAttributes().get("jarvis.userId");}
    }
    public CompanionSocket(CompanionTasks tasks,ObjectMapper mapper){
        this.tasks=tasks;this.mapper=mapper;
        pump=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"companion-events");t.setDaemon(true);return t;});
        pump.scheduleWithFixedDelay(this::tick,250,250,TimeUnit.MILLISECONDS);
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if(!(session.getAttributes().get("jarvis.userId") instanceof String)){session.close(CloseStatus.POLICY_VIOLATION);return;}
        Link link=new Link(new org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator(session,2000,262144));
        synchronized(link){links.put(session.getId(),link);send(link,"system.connected",Map.of("contractVersion",CompanionTasks.version(),"serverInstanceId",tasks.serverInstanceId()));}
    }
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message) throws Exception {
        Link link=links.get(session.getId());if(link==null)return;
        synchronized(link){
            try {
                var command=mapper.readTree(message.getPayload());
                if(command==null || !command.isObject()){send(link,"system.error",Map.of("error","Expected a JSON object"));return;}
                switch(command.path("type").asText()) {
                    case "state.sync" -> {
                        // Hold the journal lock across snapshot/cursor capture to avoid missing transitions.
                        Map<String,Object> snapshot;
                        synchronized(tasks){link.cursor=tasks.replay(link.owner,Long.MAX_VALUE).latestSequence();snapshot=tasks.state(link.owner);}
                        send(link,"system.snapshot",snapshot);
                        link.subscribed=true;
                    }
                    case "events.resume" -> {
                        if(!command.path("afterSequence").isIntegralNumber() || !command.path("afterSequence").canConvertToLong()) {send(link,"system.error",Map.of("error","afterSequence must be an integer"));break;}
                        if(command.has("serverInstanceId") && !tasks.serverInstanceId().equals(command.path("serverInstanceId").asText())) {link.subscribed=false;send(link,"system.reset_required",Map.of("serverInstanceId",tasks.serverInstanceId()));break;}
                        link.cursor=command.path("afterSequence").asLong();link.subscribed=true;
                        deliver(link);
                        if(link.subscribed)send(link,"system.resumed",Map.of("latestSequence",link.cursor));
                    }
                    default -> send(link,"system.error",Map.of("error","Unsupported command"));
                }
            }catch(com.fasterxml.jackson.core.JsonProcessingException ex){send(link,"system.error",Map.of("error","Invalid JSON"));}
        }
    }
    private void deliver(Link link) throws Exception {
        var replay=tasks.replay(link.owner,link.cursor);
        if(replay.resetRequired()){link.subscribed=false;send(link,"system.reset_required",Map.of("latestSequence",replay.latestSequence()));return;}
        for(var event:replay.events()){link.socket.sendMessage(new TextMessage(mapper.writeValueAsString(event)));link.cursor=event.sequence();}
    }
    private void send(Link link,String type,Object payload) throws Exception {
        link.socket.sendMessage(new TextMessage(mapper.writeValueAsString(new CompanionTasks.Event(UUID.randomUUID().toString(),CompanionTasks.version(),Instant.now(),0,type,null,payload))));
    }
    private void tick() {
        for(Link link:links.values()) {
            if(!link.draining.compareAndSet(false,true)) continue;
            delivery.execute(()->{
                try {
                    synchronized(link) {
                        if(link.subscribed) deliver(link);
                    }
                } catch(Exception ex) {
                    links.remove(link.socket.getId());
                    try { link.socket.close(CloseStatus.SERVER_ERROR); } catch(Exception ignored) {}
                } finally { link.draining.set(false); }
            });
        }
    }
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){links.remove(session.getId());}
    @PreDestroy public void close(){pump.shutdownNow();delivery.shutdownNow();for(Link link:links.values())try{link.socket.close(CloseStatus.GOING_AWAY);}catch(Exception ignored){}links.clear();}
}
