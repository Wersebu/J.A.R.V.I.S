package com.jarvis.core.event;

import com.jarvis.api.websocket.CognitiveEventBroadcaster;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.diagnostics.ExecutionTracer;
import com.jarvis.common.event.CognitiveEvent;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Thread-local request scoped implementation of the cognitive event bus.
 */
@Service
public class DefaultCognitiveEventBus implements CognitiveEventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultCognitiveEventBus.class);
    private static final ThreadLocal<RequestScope> REQUEST_SCOPE = new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false) private org.springframework.beans.factory.ObjectProvider<com.jarvis.common.companion.CompanionEventSink> agentEvents;
    private final ExecutionTracer executionTracer;
    private static final java.util.Set<CognitiveEventType> ANSWER_EVENTS=java.util.Set.of(CognitiveEventType.ANSWER_STREAM_STARTED,CognitiveEventType.ANSWER_STARTED,CognitiveEventType.ANSWER_TOKEN,CognitiveEventType.TOKEN,CognitiveEventType.ANSWER_FINISHED,CognitiveEventType.STREAMING_STARTED,CognitiveEventType.STREAMING_FINISHED);
    private static final ThreadLocal<Publication> PUBLICATION=new ThreadLocal<>();
    private static final class Publication {
        boolean publishing,started,committed,persisted;java.util.UUID messageId;final StringBuilder text=new StringBuilder();
        final long began=System.nanoTime();long firstToken=-1,firstVisible=-1,firstSocket=-1,generationDone=-1;int chunks;
        long elapsed(){return (System.nanoTime()-began)/1_000_000;}
    }
    @Override public String streamedAnswer(){var p=PUBLICATION.get();return p==null?"":p.text.toString();}
    private Map<String,Object> answerMetadata(Publication p){var scope=REQUEST_SCOPE.get();var data=new java.util.LinkedHashMap<String,Object>();data.put("requestId",scope.requestId());data.put("conversationId",scope.conversationId());data.put("messageId",p.messageId.toString());data.put("persisted",p.persisted);data.put("source",p.persisted?"persisted-assistant-response":"live-assistant-response");return data;}
    private void visible(String text){
        if(text==null||text.isEmpty())return;
        var p=PUBLICATION.get();if(p.committed)throw new IllegalStateException("Answer already committed");
        p.publishing=true;
        try{
            var data=answerMetadata(p);
            if(!p.started){p.started=true;publish(CognitiveEventType.ANSWER_STREAM_STARTED,"STARTED","Answer stream started",null,Map.copyOf(data));publish(CognitiveEventType.ANSWER_STARTED,"ANSWERING","Answer started",null,Map.copyOf(data));publish(CognitiveEventType.STREAMING_STARTED,"STREAMING","Streaming started",null,Map.copyOf(data));}
            if(p.firstVisible<0)p.firstVisible=p.elapsed();p.text.append(text);data.put("text",text);data.put("index",++p.chunks);
            publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN",text,null,Map.copyOf(data));
            if(p.firstSocket<0)p.firstSocket=p.elapsed();
            publish(CognitiveEventType.TOKEN,"TOKEN",text,null,Map.copyOf(data));
        }finally{p.publishing=false;}
    }
    @Override public void answerCommitted(com.jarvis.common.memory.ConversationMessage message){
        var scope=REQUEST_SCOPE.get();var p=PUBLICATION.get();
        if(scope==null||p==null||!scope.requestId().equals(message.requestId())||!scope.conversationId().equals(message.conversationId())||!p.messageId.equals(message.id()))throw new IllegalStateException("Answer publication identity mismatch");
        if(p.committed){if(p.text.toString().equals(message.content()))return;throw new IllegalStateException("A request cannot commit two different answers");}
        p.persisted=true;
        if(p.text.isEmpty())visible(message.content());
        if(!p.text.toString().equals(message.content()))throw new IllegalStateException("Streamed answer differs from persisted answer");
        p.publishing=true;
        try{
            var data=answerMetadata(p);data.put("characters",p.text.length());data.put("tokensStreamed",p.chunks);
            publish(CognitiveEventType.ANSWER_FINISHED,"FINISHED","Answer committed",null,Map.copyOf(data));
            publish(CognitiveEventType.STREAMING_FINISHED,"FINISHED","Streaming finished",null,Map.copyOf(data));
            var metrics=answerMetadata(p);metrics.put("timeToFirstTokenMs",p.firstToken);metrics.put("timeToFirstVisibleAnswerTokenMs",p.firstVisible);metrics.put("timeToFirstWebSocketAnswerEventMs",p.firstSocket);metrics.put("generationCompletedMs",p.generationDone);metrics.put("answerChunks",p.chunks);
            publish(CognitiveEventType.ANSWER_STREAM_METRICS,"FINISHED","Answer stream measurements",null,Map.copyOf(metrics));p.committed=true;
        }finally{p.publishing=false;}
    }
    private static final ThreadLocal<com.jarvis.common.ai.RequestTokenUsage> USAGE = new ThreadLocal<>();

    /**
     * Creates the event bus.
     *
     * @param executionTracer execution tracer
     */
    public DefaultCognitiveEventBus(ExecutionTracer executionTracer) {
        this.executionTracer = executionTracer;
    }

    /**
     * Starts a request-scoped event stream.
     *
     * @param requestId unique request identifier
     * @param conversationId conversation identifier
     * @param sink event sink
     */
    @Override
    public void startRequest(String requestId, String conversationId, Consumer<CognitiveEvent> sink) {
        REQUEST_SCOPE.set(new RequestScope(requestId, conversationId, sink == null ? event -> { } : sink, null, null));
        USAGE.set(new com.jarvis.common.ai.RequestTokenUsage());
        var publication=new Publication();publication.messageId=com.jarvis.common.event.AssistantResponse.messageId(com.jarvis.common.auth.CurrentUserContext.requiredUserId(),conversationId,requestId);PUBLICATION.set(publication);
        executionTracer.start(requestId, conversationId, sink);
    }

    /**
     * Finishes a request-scoped event stream.
     */
    @Override
    public void finishRequest() {
        try { executionTracer.finish(); }
        finally { REQUEST_SCOPE.remove(); USAGE.remove(); PUBLICATION.remove(); }
    }

    /**
     * Updates selected brain metadata for following events.
     *
     * @param brain selected brain
     * @param model selected model
     */
    @Override
    public void updateBrain(BrainType brain, String model) {
        RequestScope scope = REQUEST_SCOPE.get();
        if (scope != null) {
            REQUEST_SCOPE.set(new RequestScope(scope.requestId(), scope.conversationId(), scope.sink(), brain, model));
        }
    }

    /**
     * Publishes a cognitive event in the active request scope.
     *
     * @param event event type
     * @param status short status
     * @param message message
     * @param nodeId logical node identifier, when available
     * @param metadata metadata
     */
    @Override
    public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        if(event==CognitiveEventType.TOKEN_USAGE)com.jarvis.common.companion.AgentScope.usage(metadata);
        RequestScope scope = REQUEST_SCOPE.get();
        if (scope == null) {
            return;
        }
        var task=com.jarvis.common.companion.TaskExecutionContext.current();
        if(task!=null){var correlated=new java.util.LinkedHashMap<String,Object>(metadata);correlated.put("taskId",task);correlated.put("agentId",task);correlated.put("requestId",scope.requestId());correlated.put("conversationId",scope.conversationId());metadata=correlated;
            if(agentEvents!=null&&java.util.Set.of(CognitiveEventType.THINKING_STARTED,CognitiveEventType.THINKING_FINISHED,CognitiveEventType.TOKEN_USAGE,CognitiveEventType.MODEL_REQUEST_STARTED).contains(event)){
                var journal=agentEvents.getIfAvailable();if(journal!=null)journal.record(com.jarvis.common.auth.CurrentUserContext.requiredUserId(),event==CognitiveEventType.TOKEN_USAGE?"agent.inference.usage":event==CognitiveEventType.MODEL_REQUEST_STARTED?"agent.inference.started":event==CognitiveEventType.THINKING_STARTED?"agent.reasoning.started":"agent.reasoning.finished",task,metadata);
            }
        }
        var publication=PUBLICATION.get();
        if(event==CognitiveEventType.THINKING_TOKEN)return; // Private reasoning is never a user-visible channel.
        if(publication!=null){
            if(event==CognitiveEventType.FIRST_TOKEN_RECEIVED&&publication.firstToken<0)publication.firstToken=publication.elapsed();
            if(!publication.publishing&&((event==CognitiveEventType.EXECUTION_TRACE&&"NATIVE_TOOL_MODEL_TURN".equals(metadata.get("stage"))&&"FINISHED".equals(status))||event==CognitiveEventType.STREAMING_FINISHED))publication.generationDone=publication.elapsed();
            if(ANSWER_EVENTS.contains(event)&&!publication.publishing){
                if(event==CognitiveEventType.ANSWER_TOKEN&&!"progress-update".equals(metadata.get("source")))visible(String.valueOf(metadata.getOrDefault("text",message)));
                return;
            }
        }
        if (event == CognitiveEventType.TOKEN_USAGE && USAGE.get() != null) {
            metadata = USAGE.get().record(metadata);
        }
        CognitiveEvent cognitiveEvent = new CognitiveEvent(
                scope.requestId(),
                scope.conversationId(),
                Instant.now(),
                event,
                status,
                message,
                scope.brain(),
                scope.model(),
                nodeId,
                metadata
        );
        scope.sink().accept(cognitiveEvent);
        executionTracer.record(event, status, message, scope.brain(), scope.model(), nodeId, metadata);
    }

    @Override
    public void publishBackground(
            String requestId,
            String conversationId,
            CognitiveEventType event,
            String status,
            String message,
            String nodeId,
            Map<String, Object> metadata
    ) {
        LOGGER.info("[JARVIS][MEMORY][sourceRequestId={}] event={} status={} conversationId={} nodeId={} metadata={}",
                requestId, event, status, conversationId, nodeId, metadata == null ? Map.of() : metadata);
        if (REQUEST_SCOPE.get() != null) {
            // Already delivered through the active request's own sink via publish(...);
            // broadcasting here too would duplicate it and leak it to every other session.
            return;
        }
        CognitiveEvent cognitiveEvent = new CognitiveEvent(
                requestId,
                conversationId,
                Instant.now(),
                event,
                status,
                message,
                null,
                null,
                nodeId,
                metadata
        );
        CognitiveEventBroadcaster.publish(cognitiveEvent);
    }

    private record RequestScope(
            String requestId,
            String conversationId,
            Consumer<CognitiveEvent> sink,
            BrainType brain,
            String model
    ) {
    }
}
