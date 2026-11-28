package com.jarvis.common.ai;

import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class VisibleModelStreamTest {
    @Test void legacyAnswerActionIsPublishedOnceAfterEnvelopeValidation(){
        var visible=new ArrayList<String>();
        var result=VisibleModelStream.generate(provider("answer"),new Brain(BrainType.FAST,"test","test",""),List.of(),AIJobType.CHAT,visible::add);
        assertEquals("Confirmed facts",result.content());
        assertEquals(List.of("Confirmed facts"),visible);
    }
    @Test void operationalActionIsNeverReleasedAsAnAnswer(){
        var visible=new ArrayList<String>();
        assertThrows(IllegalStateException.class,()->VisibleModelStream.generate(provider("TOOLS"),new Brain(BrainType.FAST,"test","test",""),List.of(),AIJobType.CHAT,visible::add));
        assertTrue(visible.isEmpty());
    }
    private AIProvider provider(String action){return new AIProvider(){
        public String provider(){return "test";}
        public boolean supportsStreamingChat(){return true;}
        public ChatResponse chat(Brain b,String p){throw new UnsupportedOperationException();}
        public void stream(String c,Brain b,String p,ChatEventSink s){throw new UnsupportedOperationException();}
        public ModelResponse streamChat(Brain b,List<ModelMessage> m,List<NativeToolDefinition> t,Map<String,Object> schema,AIJobType j,Consumer<ModelStreamEvent> sink){
            String raw="{\"action\":\""+action+"\",\"answer\":\"Confirmed facts\"}";
            for(char c:raw.toCharArray())sink.accept(ModelStreamEvent.content(String.valueOf(c)));
            return new ModelResponse(raw,"",List.of(),"stop",new ModelUsage(10,10,20));
        }
    };}
}
