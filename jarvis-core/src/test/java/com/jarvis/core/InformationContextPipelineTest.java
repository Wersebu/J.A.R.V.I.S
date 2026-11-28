package com.jarvis.core;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import com.jarvis.common.event.*;
import com.jarvis.ollama.ModelMetadataResolver;
import com.jarvis.tools.*;
import com.jarvis.tools.information.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
/** Full production pipeline, real budgets/compression/tools, scripted models and controlled source data. */
@TestPropertySource(properties={"jarvis.inference.physical-context-window=8192","jarvis.tools.max-calls-fast=20"})
class InformationContextPipelineTest extends MultiEngineCognitivePipelineTest {
 @MockBean ToolManager toolManager;
 @MockBean InformationHttpClient source;
 @MockBean ModelMetadataResolver metadata;
 @Autowired WeatherTool weather;
 @Autowired ExchangeRateTool exchange;
 @Autowired com.jarvis.api.companion.CompanionTasks tasks;
 @Autowired com.jarvis.memory.ConversationMemoryService conversationMemory;
 final ObjectMapper json=new ObjectMapper();
 final List<Map<String,Object>> measurements=new ArrayList<>();
 @BeforeEach void fixtures()throws Exception{
  when(metadata.resolve(anyString())).thenAnswer(i->new ModelMetadataResolver.Metadata(i.getArgument(0),"fixture",8192,System.currentTimeMillis()));
  when(source.get(any())).thenAnswer(i->{String url=i.getArgument(0).toString();if(url.contains("geocoding"))return json.readTree("{\"results\":[{\"name\":\"Warsaw\",\"country\":\"Poland\",\"latitude\":52.23,\"longitude\":21.01,\"timezone\":\"Europe/Warsaw\"}]}");if(url.contains("nbp"))return json.readTree("{\"code\":\"USD\",\"rates\":[{\"effectiveDate\":\"2026-11-27\",\"mid\":3.91,\"no\":\"200/A/NBP/2026\"}]}");return json.readTree("{\"timezone\":\"Europe/Warsaw\",\"current\":{\"temperature_2m\":8,\"apparent_temperature\":6,\"precipitation\":0.2,\"wind_speed_10m\":12},\"current_units\":{\"temperature_2m\":\"C\"},\"daily\":{\"time\":[\"2026-11-29\"],\"temperature_2m_max\":[9]},\"daily_units\":{\"temperature_2m_max\":\"C\"}}");});
  when(toolManager.findTool(anyString())).thenAnswer(i->{var tool=mock(JarvisTool.class);when(tool.getName()).thenReturn(i.getArgument(0));return Optional.of(tool);});
  when(toolManager.execute(any())).thenAnswer(i->{ToolRequest r=i.getArgument(0);if(r.toolName().equals("weather"))return weather.execute(r);if(r.toolName().equals("exchange"))return exchange.execute(r);
   Map<String,Object> data=r.toolName().equals("gmail")?Map.of("messages",java.util.stream.IntStream.range(0,5).mapToObj(n->Map.of("id","mail-"+n,"subject","Project update "+n,"body","mail evidence ".repeat(10000))).toList(),"source","gmail://messages"):Map.of("url",r.arguments().getOrDefault("url","https://example.org/evidence"),"content","<nav>menu ads menu</nav>\n".repeat(5000)+"Verified research evidence: result 42.\n"+"Repeated footer\n".repeat(5000),"source","https://example.org/evidence","liveEvidenceSatisfied",true);
   return new ToolResult(true,r.toolName(),r.operation(),r.requestId(),r.conversationId(),false,List.of(),"Controlled source result",data,"","",false,"");
  });
 }
 @Test void allRequestedInformationScenariosFit8192PerSlotOnBothEngines()throws Exception{
  String[] queries={"Jaka pogoda w Warszawie?","Jaka będzie pogoda jutro?","Jaki jest kurs dolara?","Sprawdź 5 ostatnich maili.","Sprawdź pogodę w Warszawie, kurs dolara i ostatnie maile, a następnie przygotuj jedną odpowiedź.","Odczytaj bardzo dużą stronę internetową.","Wykonaj wieloetapowy research porównujący trzy źródła.","Kontynuuj długą rozmowę z wieloma wynikami narzędzi."};
  for(String engine:List.of("ollama","llamacpp"))for(int scenario=0;scenario<queries.length;scenario++){
   final int caseId=scenario;AIProvider active=engine.equals("ollama")?ollama:llama,inactive=engine.equals("ollama")?llama:ollama;
   var selected=new InferenceControl.Selection(UUID.randomUUID().toString(),engine,engine+"-fixture",Map.of("contextSize",24576,"parallelSlots",3,"maxOutputTokens",1024,"declaredCapabilities",List.of("STRUCTURED_OUTPUT","TOOLS")));when(manager.selection()).thenReturn(selected);
   var modelCalls=new AtomicInteger();var maxContext=new AtomicInteger();var toolRounds=new AtomicInteger();
   List<ModelToolCall> firstCalls=calls(caseId,0);String family=caseId==2?"exchange":caseId==3?"gmail":caseId>=5?"web":"weather";
   List<String> families=caseId==4?List.of("weather","exchange","gmail"):List.of(family);
   doAnswer(i->{modelCalls.incrementAndGet();List<ModelMessage> messages=i.getArgument(1);List<NativeToolDefinition> offered=i.getArgument(2);maxContext.accumulateAndGet(ModelContextBudget.total(messages,offered),Math::max);

    return new ModelResponse(json.writeValueAsString(Map.of("action","TOOLS","answer","","families",families)),"",List.of(),"stop",new ModelUsage(100,20,120));
   }).when(active).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());
   doAnswer(i->{List<NativeToolDefinition> offered=i.getArgument(2);if(offered.stream().anyMatch(t->t.name().equals("conversation__title")))return new ModelResponse("Information lookup","",List.of(),"stop",new ModelUsage(10,5,15));
    modelCalls.incrementAndGet();List<ModelMessage> messages=i.getArgument(1);int occupancy=ModelContextBudget.total(messages,offered);maxContext.accumulateAndGet(occupancy,Math::max);assertThat(occupancy).isLessThan(8192-1024);
    Set<String> pending=new HashSet<>();for(var m:messages){for(var c:m.toolCalls())pending.add(c.id());if(m.role().equals("tool"))assertThat(pending.remove(m.toolCallId())).as("native tool result must retain its call ID").isTrue();}
    assertThat(pending).isEmpty();
    long toolCount=messages.stream().filter(m->m.role().equals("tool")).count();int round=toolRounds.getAndIncrement();int wanted=caseId==6?3:caseId==7?12:1;
    if(toolCount==0||round<wanted-1)return new ModelResponse("","",calls(caseId,round+1),"tool_calls",new ModelUsage(occupancy,20,occupancy+20));
    String answer=caseId==2?"Kurs referencyjny NBP: USD/PLN 3,91, data publikacji 2026-11-27.":caseId==3?"Sprawdziłem pięć ostatnich wiadomości: Project update 0, 1, 2, 3, 4.":caseId==4?"Warszawa: 8 C. USD/PLN 3,91 (NBP, 2026-11-27). Pięć wiadomości: Project update 0, 1, 2, 3, 4.":caseId>=5?"Weryfikacja źródeł: wynik 42. Źródło: https://example.org/evidence.":"Warszawa: 8 C obecnie, jutro do 9 C. Źródło: Open-Meteo.";
    return new ModelResponse(answer,"",List.of(),"stop",new ModelUsage(occupancy,60,occupancy+60));
   }).when(active).toolChat(any(),anyList(),anyList(),any());
   clearInvocations(inactive);long started=System.nanoTime();String conversation=engine+"-"+(caseId<2?"weather-followup":"information-"+caseId);
   if(caseId==7)com.jarvis.common.auth.CurrentUserContext.runAs("engine-test",()->{for(int n=0;n<40;n++)conversationMemory.addMessage(conversation,com.jarvis.common.memory.ConversationMessage.chat(conversation,"old-"+n,com.jarvis.common.memory.MessageRole.ASSISTANT,"Earlier sourced result "+n+" "+"reference data ".repeat(400),java.time.Instant.now()));});
   var result=request(queries[scenario],conversation);long ms=(System.nanoTime()-started)/1000000;
   assertThat(result.response()).doesNotContain("CONTEXT_BUDGET_EXCEEDED","tury modelu","Wykonano:");assertThat(result.response()).contains(caseId==2?"3,91":caseId==3?"Project update":caseId==4?"USD/PLN":caseId>=5?"42":"Warszawa");
   verify(inactive,never()).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());assertThat(tasks.list("engine-test")).isEmpty();
   measurements.add(Map.of("engine",engine,"query",queries[scenario],"modelCalls",modelCalls.get(),"elapsedMs",ms,"maximumEstimatedContextTokens",maxContext.get(),"contextPerSlot",8192,"sourceMode","CONTROLLED_FIXTURES","modelMode","SCRIPTED_NOT_REAL_MODEL_QUALITY"));
  }
  Path target=Path.of(System.getProperty("maven.multiModuleProjectDirectory",".")).resolve("information-pipeline-results.json");Files.createDirectories(target.toAbsolutePath().getParent());Files.writeString(target,json.writerWithDefaultPrettyPrinter().writeValueAsString(measurements));
 }
 List<ModelToolCall> calls(int which,int round){var out=new ArrayList<ModelToolCall>();
  if(which==0||which==1||which==4)out.add(new ModelToolCall("weather-"+round,"weather__get",which==1?Map.of("location","Warszawa","mode","daily","date","tomorrow","days",1):Map.of("location","Warszawa")));
  if(which==2||which==4)out.add(new ModelToolCall("exchange-"+round,"exchange__rate",Map.of("currency","USD")));
  if(which==3||which==4)out.add(new ModelToolCall("mail-"+round,"gmail__list",Map.of("limit",5)));
  if(which>=5)out.add(new ModelToolCall("page-"+round,"web__read_web_page",Map.of("url","https://example.org/evidence/"+round)));
  return out;
 }
}
