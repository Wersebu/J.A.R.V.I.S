package com.jarvis.common.ai;
import com.fasterxml.jackson.databind.*;
import java.util.*;
/** Rejects operational payloads by their provenance/schema, never by stripping arbitrary JSON. */
public final class FinalAnswerGuard {
 private static final ObjectMapper JSON=new ObjectMapper();
 private FinalAnswerGuard(){}
 public static boolean toolArguments(String text,List<NativeToolDefinition> tools,String request){
  if(text==null||text.isBlank())return false;
  // Requested structured output is a valid user deliverable, not an operational payload by shape alone.
  if(request!=null&&request.toLowerCase(Locale.ROOT).matches("(?s).*\\bjson\\b.*"))return false;
  JsonNode value;try{value=JSON.readTree(text);}catch(Exception ignored){return false;}
  if(value==null||!value.isObject()||value.isEmpty())return false;
  if(value.has("tool_calls")||value.has("function")&&value.path("function").has("arguments"))return true;
  Set<String> keys=new HashSet<>();value.fieldNames().forEachRemaining(keys::add);
  for(var tool:tools){Object properties=tool.parameters().get("properties");if(!(properties instanceof Map<?,?> fields)||!fields.keySet().containsAll(keys))continue;
   Object required=tool.parameters().get("required");if(required instanceof List<?> names&&!names.isEmpty()&&keys.containsAll(names))return true;
  }
  return false;
 }
 public static void require(ModelResponse response,List<NativeToolDefinition> tools,String request){
  if(response.hasToolCalls()||response.content().isBlank()||Set.of("length","max_tokens").contains(response.finishReason())||toolArguments(response.content(),tools,request))throw new AIProviderException("INVALID_FINAL_ANSWER: operational or incomplete response is not a deliverable");
 }
}
