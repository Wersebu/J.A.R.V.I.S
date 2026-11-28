package com.jarvis.tools.runtime;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.jarvis.common.ai.ModelContextBudget;
import java.util.*;
import java.util.regex.*;
/** Deterministic, explicitly partial views. Original evidence stays owner-scoped in ToolOutputStore. */
public final class ToolResultCompression {
 private static final ObjectMapper JSON=new ObjectMapper();
 private ToolResultCompression(){}
 public static String compress(String raw,String query,int tokens,ToolOutputStore store){
  if(ModelContextBudget.tokens(raw)<=tokens)return raw;
  if(store==null)throw new com.jarvis.common.ai.AIProviderException("CONTEXT_BUDGET_EXCEEDED: output store unavailable");
  JsonNode original;try{original=JSON.readTree(raw);}catch(Exception e){original=TextNode.valueOf(raw);}
  String existing=original==null?"":original.path("_shortened").path("outputId").asText("");
  String id=existing.isBlank()?store.put(raw):existing;
  int chars=Math.max(64,tokens);
  for(int attempt=0;attempt<14;attempt++){
   ObjectNode result=JSON.createObjectNode();
   result.putObject("_shortened").put("outputId",id).put("originalTokens",original==null?ModelContextBudget.tokens(raw):original.path("_shortened").path("originalTokens").asInt(ModelContextBudget.tokens(raw))).put("partial",true).put("readWith","output__read or output__grep");
   result.set("evidence",shrink(original,query,chars,Math.max(1,chars/180),0));
   String value=result.toString();if(ModelContextBudget.tokens(value)<=tokens)return value;chars=Math.max(8,chars*2/3);
  }
  ObjectNode result=JSON.createObjectNode();result.putObject("_shortened").put("outputId",id).put("partial",true);result.put("note","Evidence archived; retrieve a specific range with output__read");
  return result.toString();
 }
 private static JsonNode shrink(JsonNode node,String query,int chars,int items,int depth){
  if(node==null)return NullNode.instance;if(depth>8)return TextNode.valueOf("[nested data archived]");
  if(node.isTextual())return TextNode.valueOf(excerpt(node.asText(),query,chars));
  if(node.isArray()){var array=JSON.createArrayNode();int limit=node.size()<=10?node.size():items;int n=0;for(var item:node){if(n++>=limit)break;array.add(shrink(item,query,chars,items,depth+1));}if(node.size()>limit)array.addObject().put("omittedItems",node.size()-limit);return array;}
  if(node.isObject()){var result=JSON.createObjectNode();var fields=node.fields();int n=0;while(fields.hasNext()){var field=fields.next();if(field.getKey().equals("_shortened"))continue;if(n++>40){result.put("omittedFields",true);break;}result.set(field.getKey(),Set.of("source","url","effectiveDate","id","tool","operation").contains(field.getKey())&&field.getValue().isValueNode()&&field.getValue().toString().length()<512?field.getValue():shrink(field.getValue(),query,chars,items,depth+1));}return result;}
  return node;
 }
 public static String excerpt(String text,String query,int chars){
  String clean=text.matches("(?is).*<(?:html|body|nav|script|div)\\b.*")?text.replaceAll("(?is)<(script|style|nav|footer|header|aside)\\b[^>]*>.*?</\\1>"," ").replaceAll("(?is)<[^>]+>"," "):text;
  if(clean.length()<=chars)return clean;
  var terms=Arrays.stream(query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+" )).filter(t->t.length()>2).distinct().toList();
  var lines=new LinkedHashSet<String>();for(String line:clean.split("[\\r\\n]+|(?<=[.!?])\\s+")){String stripped=line.strip().replaceAll("\\s+"," ");if(!stripped.isBlank())lines.add(stripped);}
  var candidates=new ArrayList<>(lines);candidates.sort(Comparator.<String>comparingInt(line->{int score=line.contains("http")?3:0;String lower=line.toLowerCase(Locale.ROOT);for(String term:terms)if(lower.contains(term))score+=5;return -score;}));
  var out=new StringBuilder();for(String line:candidates){if(out.length()>=chars)break;int room=chars-out.length();out.append(line,0,Math.min(line.length(),room)).append('\n');}
  return out.substring(0,Math.min(chars,out.length()));
 }
}
