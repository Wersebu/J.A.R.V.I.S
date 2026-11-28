package com.jarvis.tools.runtime;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.*;
import com.jarvis.tools.schema.*;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class MainConversationNativeTurnTest {
 @Test void firstMainNativeCallIsConsumedOnceThenMainWritesTheAnswer(){
  var registry=mock(ToolRegistry.class);when(registry.definitions()).thenReturn(List.of(new ToolDefinition("fx","Exchange rates",List.of(new ToolOperationDefinition("QUOTE","Read quote",List.of(),false,ToolSafetyLevel.READ)))));var schemas=new NativeToolSchemaMapper(registry);
  var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("fake");String answer="Sprawdziłem dane w źródle. Potwierdzony kurs to 4 zł za dolara; to odczyt, a nie zgadywana wartość.";when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(new ModelResponse(answer,"",List.of(),"stop",new ModelUsage(10,15,25)));
  var tools=mock(ToolManager.class);var registered=mock(JarvisTool.class);when(registered.getName()).thenReturn("fx");when(tools.findTool("fx")).thenReturn(Optional.of(registered));when(tools.execute(any())).thenReturn(new ToolResult(true,"fx","QUOTE","request","conversation",false,List.of(),"Quote retrieved",Map.of("value",4,"pair","USDPLN"),"","",false,""));
  var events=mock(CognitiveEventBus.class);var service=new NativeToolLoopService(List.of(provider),tools,q->ToolIntent.NO_TOOL,new ToolRuntimeProperties(true,8,8,2,30,"native"),events,new ToolRuntimeDebugService(),new ObjectMapper(),schemas,new StoreAuditDatasetService(events));
  var first=new ModelResponse("","",List.of(new ModelToolCall("call-id","fx__quote",Map.of())),"tool_calls",new ModelUsage(20,3,23));var context=Map.<String,Object>of("semanticOrchestration",true,"executionMode","TOOLS","workflowTools",List.of("fx__quote"),"firstModelResponse",first);
  var result=service.execute(new ToolCallingRequest("request","conversation","Sprawdź kurs USD/PLN","Sprawdź kurs USD/PLN","",context,"MARKDOWN_MAIN_IDENTITY USER_SETTINGS HISTORY",new Brain(BrainType.FAST,"fake","fake",""),KnowledgeMode.FAST,List.of(),""));
  assertThat(result.finalAnswer()).isEqualTo(answer);verify(tools,times(1)).execute(any());var messages=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider,times(1)).toolChat(any(),messages.capture(),anyList(),any());assertThat(messages.getValue().stream().map(Object::toString).toList().toString()).contains("MARKDOWN_MAIN_IDENTITY","USER_SETTINGS","HISTORY");
 }
}
