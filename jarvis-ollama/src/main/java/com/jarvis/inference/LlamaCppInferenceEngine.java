package com.jarvis.inference;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.*;
import com.jarvis.ollama.InferenceDeadline;
import org.springframework.stereotype.Service;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
/** OpenAI-compatible llama-server transport behind existing Core AIProvider. */
@Service
public class LlamaCppInferenceEngine implements AIProvider {
 private final ObjectMapper mapper;private final InferenceProperties properties;
 private final InferenceEngineManager manager;private final EngineAccessGate gate;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
 private final Semaphore slots=new Semaphore(1,true);
 public LlamaCppInferenceEngine(ObjectMapper mapper,InferenceProperties properties,InferenceEngineManager manager,EngineAccessGate gate){this.mapper=mapper;this.properties=properties;this.manager=manager;this.gate=gate;}
 public String provider(){return "llamacpp";}
 public boolean supportsStreamingChat(){return true;}
 public ChatResponse chat(Brain brain,String prompt){return new ChatResponse(toolChat(brain,List.of(ModelMessage.user(prompt)),List.of(),AIJobType.CHAT).content());}
 public void stream(String conversation,Brain brain,String prompt,ChatEventSink sink){stream(conversation,brain,prompt,AIJobType.CHAT,List.of(),sink);}
 public void stream(String conversation,Brain brain,String prompt,AIJobType job,List<ImageAttachment> images,ChatEventSink sink){
  long start=System.nanoTime();var result=streamChat(brain,List.of(ModelMessage.user(prompt,images)),List.of(),Map.of(),job,e->{
   if(e.type()==ModelStreamEvent.Type.CONTENT)sink.publish(TokenEvent.create(conversation,e.text()));
   if(e.type()==ModelStreamEvent.Type.REASONING)sink.publish(ThinkingTokenEvent.create(conversation,e.text()));
  });long ms=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
  sink.publish(GenerationFinishedEvent.create(conversation,ms,brain.type(),brain.model(),result.usage().promptTokens(),result.usage().completionTokens(),null));
 }
 public ModelResponse toolChat(Brain brain,List<ModelMessage> messages,List<NativeToolDefinition> tools,AIJobType job){return streamChat(brain,messages,tools,Map.of(),job,e->{});}
 public ModelResponse streamChat(Brain brain,List<ModelMessage> messages,List<NativeToolDefinition> tools,Map<String,Object> schema,AIJobType job,Consumer<ModelStreamEvent> sink){
  try(var residency=gate.enter(provider())){
   var selected=manager.selection();if(!selected.model().equals(brain.model())||!brain.configurationId().isEmpty()&&!selected.configurationId().equals(brain.configurationId()))throw new AIProviderException("STALE_CONFIGURATION: model changed; task was not reassigned");
   manager.validateCapabilities(messages,tools,schema);
   if(!slots.tryAcquire(30,TimeUnit.SECONDS))throw new AIProviderException("ENGINE_BUSY");
   try(var deadline=new InferenceDeadline()){
    var p=selected.parameters();Map<String,Object> body=new LinkedHashMap<>();body.put("model",brain.model());body.put("stream",true);body.put("stream_options",Map.of("include_usage",true));
    body.put("messages",messages.stream().map(this::message).toList());
    if(!tools.isEmpty())body.put("tools",tools.stream().map(t->Map.of("type","function","function",Map.of("name",t.name(),"description",t.description(),"parameters",t.parameters()))).toList());
    if(!schema.isEmpty())body.put("response_format",Map.of("type","json_schema","json_schema",Map.of("name","response","strict",true,"schema",schema)));
    Map<String,String> mapping=Map.of("maxOutputTokens","max_tokens","temperature","temperature","topP","top_p","topK","top_k","repeatPenalty","repeat_penalty","seed","seed","reasoningEffort","reasoning_effort");
    mapping.forEach((k,v)->{if(p.containsKey(k))body.put(v,p.get(k));});
    Duration timeout=Duration.ofSeconds(((Number)p.getOrDefault("timeoutSeconds",120)).longValue());
    var request=HttpRequest.newBuilder(URI.create(properties.baseUrl()+"/v1/chat/completions")).timeout(timeout).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
    long started=System.nanoTime();long[] first={0};
    var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
    try(var input=response.body()){
     InferenceDeadline.attach(input);
     // HttpRequest timeout ends at headers for InputStream bodies; close stalled streams explicitly.
     try(var timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"llama-body-timeout");t.setDaemon(true);return t;})){
      var alarm=timer.schedule(()->{try{input.close();}catch(IOException ignored){}},timeout.toMillis(),TimeUnit.MILLISECONDS);
      try{
       if(response.statusCode()!=200)throw new AIProviderException("BACKEND_HTTP_"+response.statusCode());
       var result=new OpenAiStreamDecoder(mapper).decode(new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)),e->{if(e.type()==ModelStreamEvent.Type.FIRST_TOKEN)first[0]=System.nanoTime();sink.accept(e);});
       manager.metrics(started,first[0],result.usage());return result;
      }finally{alarm.cancel(false);timer.shutdownNow();}
     }
    }
   }finally{slots.release();}
  }catch(InterruptedException e){Thread.currentThread().interrupt();throw new AIProviderException("CANCELLED",e);}
   catch(IOException e){throw new AIProviderException("INFERENCE_TRANSPORT_FAILED",e);}
 }
 private Map<String,Object> message(ModelMessage m){
  Map<String,Object> row=new LinkedHashMap<>();row.put("role",m.role());row.put("content",m.content());
  if(!m.images().isEmpty()){
   var parts=new ArrayList<Map<String,Object>>();parts.add(Map.of("type","text","text",m.content()));
   for(var image:m.images()){byte[] bytes;try{bytes=Base64.getDecoder().decode(image.base64Data());}catch(IllegalArgumentException e){throw new AIProviderException("INVALID_IMAGE");}
    String mime=bytes.length>3&&(bytes[0]&255)==0xff&&(bytes[1]&255)==0xd8?"image/jpeg":bytes.length>8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71?"image/png":null;
    if(mime==null)throw new AIProviderException("UNSUPPORTED_IMAGE_FORMAT: PNG/JPEG required");
    parts.add(Map.of("type","image_url","image_url",Map.of("url","data:"+mime+";base64,"+image.base64Data())));
   }row.put("content",parts);
  }
  if(!m.toolCallId().isEmpty())row.put("tool_call_id",m.toolCallId());
  if(!m.toolCalls().isEmpty())row.put("tool_calls",m.toolCalls().stream().map(t->{try{return Map.of("id",t.id(),"type","function","function",Map.of("name",t.name(),"arguments",mapper.writeValueAsString(t.arguments())));}catch(Exception e){throw new AIProviderException("INVALID_TOOL_ARGUMENTS",e);}}).toList());
  return row;
 }
}
