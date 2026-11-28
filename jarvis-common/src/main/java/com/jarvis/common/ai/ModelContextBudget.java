package com.jarvis.common.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Heuristic estimates, never advertised as tokenizer measurements. Image reserve excludes base64 transport. */
public final class ModelContextBudget {
 private static final ObjectMapper JSON=new ObjectMapper();
 private ModelContextBudget(){}
 public static int tokens(String text){return text==null?0:(text.getBytes(StandardCharsets.UTF_8).length+2)/3;}
 public static int serialized(Object value){try{return tokens(JSON.writeValueAsString(value));}catch(Exception e){throw new IllegalArgumentException("Cannot measure context",e);}}
 public static Map<String,Object> occupancy(List<ModelMessage> messages,Object tools,int inputBudget){
  long system=0,history=0,results=0,images=0,calls=0;
  for(var m:messages){long n=tokens(m.content())+64;if(m.role().equals("system"))system+=n;else if(m.role().equals("tool"))results+=n;else history+=n;images+=2048L*m.images().size();calls+=serialized(m.toolCalls());}
  long definitions=serialized(tools),total=system+history+results+images+calls+definitions+128;
  var out=new LinkedHashMap<String,Object>();out.put("estimatedContextTokens",total);out.put("availableInputTokens",Math.max(0,inputBudget-total));out.put("inputBudgetTokens",inputBudget);out.put("systemPromptTokens",system);out.put("conversationHistoryTokens",history);out.put("toolResultTokens",results);out.put("toolDefinitionTokens",definitions);out.put("toolCallTokens",calls);out.put("imageReserveTokens",images);out.put("measurement","UTF8_HEURISTIC_NOT_TOKENIZER");return out;
 }
 public static int total(List<ModelMessage> messages,Object tools){return ((Number)occupancy(messages,tools,Integer.MAX_VALUE).get("estimatedContextTokens")).intValue();}
}
