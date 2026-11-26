package com.jarvis.common.event;

import com.jarvis.common.memory.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** One identity and non-empty body for both interactive and autonomous deliveries. */
public final class AssistantResponse {
    private AssistantResponse() {}
    public static UUID messageId(String owner,String conversation,String request) {
        return UUID.nameUUIDFromBytes((owner+"\n"+conversation+"\n"+request).getBytes(StandardCharsets.UTF_8));
    }
    public static ConversationMessage message(String owner,String conversation,String request,String text,Instant created) {
        if(text==null||text.isBlank())throw new IllegalStateException("Cannot deliver an empty assistant response");
        if(conversation==null||conversation.isBlank()||request==null||request.isBlank())throw new IllegalStateException("Missing answer identity");
        return new ConversationMessage(messageId(owner,conversation,request),conversation,request,MessageRole.ASSISTANT,text,created,0,ConversationMessageType.CHAT,ConversationMessageStatus.FINAL);
    }
    public static void publish(CognitiveEventBus bus,ConversationMessage message) {
        var data=new LinkedHashMap<String,Object>();
        data.put("requestId",message.requestId());data.put("conversationId",message.conversationId());data.put("messageId",message.id().toString());data.put("source","persisted-assistant-response");data.put("persisted",true);
        bus.publish(CognitiveEventType.ANSWER_STREAM_STARTED,"STARTED","Answer stream started",null,Map.copyOf(data));
        bus.publish(CognitiveEventType.ANSWER_STARTED,"ANSWERING","Answer started",null,Map.copyOf(data));
        bus.publish(CognitiveEventType.STREAMING_STARTED,"STREAMING","Streaming started",null,Map.copyOf(data));
        int index=0;
        for(int start=0;start<message.content().length();){
            int end=Math.min(start+2048,message.content().length());
            if(end<message.content().length()&&Character.isHighSurrogate(message.content().charAt(end-1)))end--;
            String chunk=message.content().substring(start,end);data.put("text",chunk);data.put("index",++index);
            bus.publish(CognitiveEventType.ANSWER_TOKEN,"TOKEN",chunk,null,Map.copyOf(data));
            bus.publish(CognitiveEventType.TOKEN,"TOKEN",chunk,null,Map.copyOf(data));start=end;
        }
        data.remove("text");data.put("characters",message.content().length());data.put("tokensStreamed",index);
        bus.publish(CognitiveEventType.ANSWER_FINISHED,"FINISHED","Answer finished",null,Map.copyOf(data));
        bus.publish(CognitiveEventType.STREAMING_FINISHED,"FINISHED","Streaming finished",null,Map.copyOf(data));
    }
}
