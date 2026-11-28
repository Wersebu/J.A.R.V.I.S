package com.jarvis.inference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OpenAiStreamDecoderTest {
 private ModelResponse decode(String s,List<ModelStreamEvent> events)throws Exception{return new OpenAiStreamDecoder(new ObjectMapper()).decode(new BufferedReader(new StringReader(s)),events::add);}
 private String frame(String delta,String finish){return "data: {\"choices\":[{\"index\":0,\"delta\":"+delta+",\"finish_reason\":"+finish+"}]}\n\n";}
 private final String done="data: [DONE]\n\n";
 @Test void textAndReasoningAreSeparated()throws Exception{var events=new ArrayList<ModelStreamEvent>();var r=decode(frame("{\"reasoning_content\":\"think\"}","null")+frame("{\"content\":\"answer\"}","\"stop\"")+done,events);assertThat(r.content()).isEqualTo("answer");assertThat(r.thinking()).isEqualTo("think");assertThat(events).extracting(ModelStreamEvent::type).containsExactly(ModelStreamEvent.Type.FIRST_TOKEN,ModelStreamEvent.Type.REASONING,ModelStreamEvent.Type.CONTENT);}
 @Test void assemblesIndexedToolArguments()throws Exception{
  String a="{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\"}}]}";
  String b="{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"test\\\"}\"}}]}";
  var r=decode(frame(a,"null")+frame(b,"\"tool_calls\"")+done,new ArrayList<>());assertThat(r.toolCalls()).hasSize(1);assertThat(r.toolCalls().getFirst().arguments()).containsEntry("q","test");
 }
 @Test void missingDoneFails(){assertThatThrownBy(()->decode(frame("{\"content\":\"partial\"}","\"stop\""),new ArrayList<>())).hasMessageContaining("INCOMPLETE_GENERATION");}
 @Test void missingFinishFails(){assertThatThrownBy(()->decode(frame("{\"content\":\"partial\"}","null")+done,new ArrayList<>())).hasMessageContaining("INCOMPLETE_GENERATION");}
 @Test void reasoningOnlyFails(){assertThatThrownBy(()->decode(frame("{\"reasoning_content\":\"hidden\"}","\"stop\"")+done,new ArrayList<>())).hasMessageContaining("REASONING_ONLY");}
 @Test void emptyFails(){assertThatThrownBy(()->decode(frame("{\"content\":null}","\"stop\"")+done,new ArrayList<>())).hasMessageContaining("EMPTY_RESPONSE");}
 @Test void malformedToolArgumentsFail(){assertThatThrownBy(()->decode(frame("{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"x\",\"arguments\":\"[]\"}}]}","\"tool_calls\"")+done,new ArrayList<>())).hasMessageContaining("INVALID_TOOL_ARGUMENTS");}
 @Test void usageAfterFinishIsRetained()throws Exception{var r=decode(": heartbeat\n\n"+frame("{\"content\":\"ok\"}","\"stop\"")+"data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}\n\n"+done,new ArrayList<>());assertThat(r.usage()).isEqualTo(new ModelUsage(4,2,6));}
 @Test void cancellationFails(){Thread.currentThread().interrupt();try{assertThatThrownBy(()->decode(frame("{}","null"),new ArrayList<>())).hasMessage("CANCELLED");}finally{Thread.interrupted();}}
 @Test void duplicateToolIdRejected(){String calls="{\"tool_calls\":[{\"index\":0,\"id\":\"same\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"same\",\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}]}";assertThatThrownBy(()->decode(frame(calls,"\"tool_calls\"")+done,new ArrayList<>())).hasMessageContaining("INVALID_TOOL_CALL");}
}
