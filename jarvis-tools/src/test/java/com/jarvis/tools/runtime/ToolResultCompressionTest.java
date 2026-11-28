package com.jarvis.tools.runtime;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.tools.ToolRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ToolResultCompressionTest {
 @Test void largeResultsRemainValidJsonAndFullEvidenceIsRetrievable()throws Exception{
  var json=new ObjectMapper();var store=new ToolOutputStore();String original=json.writeValueAsString(Map.of("source","https://example.org/evidence","content","Navigation\n".repeat(3000)+"Warsaw temperature 8 C\n"+"footer\n".repeat(3000)));
  String view=ToolResultCompression.compress(original,"Warsaw temperature",600,store);var node=json.readTree(view);assertThat(ModelContextBudget.tokens(view)).isLessThanOrEqualTo(600);assertThat(view).contains("Warsaw temperature 8 C","https://example.org/evidence");String id=node.path("_shortened").path("outputId").asText();assertThat(store.get(id)).contains(original);
  var page=new OutputTool(store).execute(new ToolRequest("output","READ","c","r","","",Map.of("outputId",id,"offsetChars",10000,"maxChars",100)));assertThat(page.data().get("content")).isEqualTo(original.substring(10000,10100));assertThat(page.data().get("nextOffsetChars")).isEqualTo(10100);
 }
 @Test void recompressionReusesOriginalArchiveAndRetainsNativeIds()throws Exception{
  var store=new ToolOutputStore();var raw=new ObjectMapper().writeValueAsString(Map.of("content","large data ".repeat(6000)));
  var first=ToolResultCompression.compress(raw,"data",700,store);var second=ToolResultCompression.compress(first,"data",200,store);var json=new ObjectMapper();assertThat(json.readTree(second).path("_shortened").path("outputId")).isEqualTo(json.readTree(first).path("_shortened").path("outputId"));
 }
 @Test void pathologicalMapsNeverProduceBrokenJson()throws Exception{var fields=new LinkedHashMap<String,Object>();for(int i=0;i<2000;i++)fields.put("field"+i,"x".repeat(500));var json=new ObjectMapper();assertThat(json.readTree(ContextBudget.fit(fields,400,new ToolOutputStore(),json)).isObject()).isTrue();}
}
