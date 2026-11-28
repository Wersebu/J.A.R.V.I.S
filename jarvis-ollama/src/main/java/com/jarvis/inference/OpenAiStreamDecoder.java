package com.jarvis.inference;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
/** SSE framing and indexed native tool assembly. No heuristic parsing of Harmony text. */
public final class OpenAiStreamDecoder {
 private final ObjectMapper mapper;
 public OpenAiStreamDecoder(ObjectMapper mapper){this.mapper=mapper;}
 private static final class Tool {String id="",name="";StringBuilder args=new StringBuilder();}
 public ModelResponse decode(BufferedReader reader,Consumer<ModelStreamEvent> sink) throws IOException {
  StringBuilder content=new StringBuilder(),reasoning=new StringBuilder(),data=new StringBuilder();
  Map<Integer,Tool> calls=new TreeMap<>();String finish="";boolean done=false,first=true;ModelUsage usage=new ModelUsage(0,0,0);
  String eventId="";Map<String,String> seenIds=new LinkedHashMap<>();
  String line;
  while((line=reader.readLine())!=null){
   if(Thread.currentThread().isInterrupted())throw new AIProviderException("CANCELLED");
   if(!line.isEmpty()){if(line.startsWith("id:"))eventId=line.substring(3).strip();if(line.startsWith("data:")){if(!data.isEmpty())data.append('\n');data.append(line.substring(5).stripLeading());if(data.length()>4*1024*1024)throw new AIProviderException("SSE_FRAME_TOO_LARGE");}continue;}
   if(data.isEmpty())continue;
   String payload=data.toString();data.setLength(0);
   if(!eventId.isEmpty()){String old=seenIds.putIfAbsent(eventId,payload);eventId="";if(old!=null){if(!old.equals(payload))throw new AIProviderException("CONFLICTING_SSE_EVENT_ID");continue;}if(seenIds.size()>4096)seenIds.remove(seenIds.keySet().iterator().next());}
   if(payload.equals("[DONE]")){done=true;break;}
   JsonNode frame=mapper.readTree(payload);
   if(frame.has("error"))throw new AIProviderException("BACKEND_ERROR");
   if(frame.hasNonNull("usage")){var u=frame.get("usage");usage=new ModelUsage(u.path("prompt_tokens").asInt(),u.path("completion_tokens").asInt(),u.path("total_tokens").asInt());}
   for(JsonNode choice:frame.path("choices")){
    if(choice.path("index").asInt()!=0)throw new AIProviderException("UNSUPPORTED_MULTIPLE_CHOICES");
    JsonNode delta=choice.path("delta");
    String text=delta.path("content").asText("");String thinking=delta.path("reasoning_content").asText("");
    if(thinking.isEmpty())thinking=delta.path("reasoning").asText("");
    if(first&&(!text.isEmpty()||!thinking.isEmpty()||delta.has("tool_calls"))){sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.FIRST_TOKEN,"","",""));first=false;}
    if(!text.isEmpty()){content.append(text);sink.accept(ModelStreamEvent.content(text));}
    if(!thinking.isEmpty()){reasoning.append(thinking);sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.REASONING,thinking,"",""));}
    for(JsonNode call:delta.path("tool_calls")){
     if(!call.has("index")||call.path("index").asInt(-1)<0)throw new AIProviderException("INVALID_TOOL_INDEX");
     Tool t=calls.computeIfAbsent(call.path("index").asInt(),k->new Tool());
     if(call.hasNonNull("id")){String id=call.get("id").asText();if(!t.id.isEmpty()&&!t.id.equals(id))throw new AIProviderException("CONFLICTING_TOOL_ID");t.id=id;}
     var fn=call.path("function");if(fn.hasNonNull("name"))t.name+=fn.get("name").asText();
     String args=fn.path("arguments").asText("");t.args.append(args);
     if(!args.isEmpty())sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.TOOL_ARGUMENTS_DELTA,args,t.id,t.name));
    }
    if(choice.hasNonNull("finish_reason"))finish=choice.get("finish_reason").asText();
   }
  }
  if(!done||finish.isBlank())throw new AIProviderException("INCOMPLETE_GENERATION: missing terminal SSE event");
  var tools=new ArrayList<ModelToolCall>();var ids=new HashSet<String>();
  for(Tool t:calls.values()){
   if(t.id.isBlank()||t.name.isBlank()||!ids.add(t.id))throw new AIProviderException("INVALID_TOOL_CALL");
   JsonNode args=mapper.readTree(t.args.toString());if(args==null||!args.isObject())throw new AIProviderException("INVALID_TOOL_ARGUMENTS");
   tools.add(new ModelToolCall(t.id,t.name,mapper.convertValue(args,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){})));
   sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.TOOL_CALL_COMPLETE,"",t.id,t.name));
  }
  if(content.toString().isBlank()&&tools.isEmpty())throw new AIProviderException(reasoning.isEmpty()?"EMPTY_RESPONSE":"REASONING_ONLY_RESPONSE");
  if(!Set.of("stop","tool_calls","length").contains(finish))throw new AIProviderException("GENERATION_FAILED: "+finish);
  return new ModelResponse(content.toString(),reasoning.toString(),tools,finish,usage);
 }
}
