package com.jarvis.core;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
/** Real HTTP ingress, real Ollama transport/gateway, tools and SQLite. No mocked providers/services. */
@EnabledIfSystemProperty(named="jarvis.audit.real",matches="true")
@SpringBootTest(classes=JarvisApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","knowledge.watch=false","jarvis.mcp.enabled=false","jarvis.companion.history.enabled=true","jarvis.memory.background.enabled=false","jarvis.memory.autonomous.enabled=false","jarvis.memory.dreaming.enabled=false","jarvis.diagnostics.conversation-trace.enabled=false","jarvis.inference.physical-context-window=8192","jarvis.companion.task-concurrency=4","jarvis.model-gateway.concurrency=3","jarvis.model-gateway.reserved-interactive=1","jarvis.inference.profiles.main-conversation.timeout-seconds=90","jarvis.inference.profiles.main-conversation.output-tokens=1024","jarvis.inference.profiles.agent-execution.timeout-seconds=90","jarvis.tools.max-calls-fast=5","jarvis.tools.max-calls-agent=8"})
class RealIngressAuditTest {
 static final Path ROOT=root();static Path root(){try{Path p=Files.createTempDirectory("jarvis-real-ingress-");Files.writeString(p.resolve("identity.md"),"Jesteś J.A.R.V.I.S., osobisty asystent. Zachowuj tę tożsamość. Odpowiadaj po polsku, zwięźle i uczciwie. Nie wymyślaj wyników narzędzi ani informacji o użytkowniku.");return p;}catch(Exception e){throw new RuntimeException(e);}}
 @DynamicPropertySource static void paths(DynamicPropertyRegistry p){p.add("jarvis.companion.history.path",()->ROOT.resolve("companion.db").toString());p.add("jarvis.memory.database-path",()->ROOT.resolve("memory.db").toString());p.add("knowledge.root",()->ROOT.resolve("knowledge").toString());p.add("knowledge.vault.index-database",()->ROOT.resolve("index.db").toString());p.add("jarvis.workspace.root",()->ROOT.resolve("workspaces").toString());p.add("jarvis.ai.identity-file",()->"file:"+ROOT.resolve("identity.md"));p.add("jarvis.model.active-model-file",()->ROOT.resolve("active-model.txt").toString());p.add("jarvis.inference.engines.configuration-file",()->ROOT.resolve("inference.json").toString());}
 @Autowired TestRestTemplate http;@Autowired com.jarvis.api.companion.CompanionTasks tasks;@Autowired com.jarvis.common.companion.CompanionDocumentStore store;
 final ObjectMapper json=new ObjectMapper().findAndRegisterModules();String token,owner;List<Map<String,Object>> report=new ArrayList<>();
 JsonNode request(HttpMethod method,String path,Object body)throws Exception{var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);if(token!=null)headers.setBearerAuth(token);var response=http.exchange(path,method,new HttpEntity<>(body,headers),String.class);if(!response.getStatusCode().is2xxSuccessful())throw new AssertionError(path+" returned "+response.getStatusCode()+": "+response.getBody());return json.readTree(response.getBody()==null?"{}":response.getBody());}
 @Test void realIngressAndTaskLifecycle()throws Exception{
  try{
   var auth=request(HttpMethod.POST,"/api/v1/auth/bootstrap",Map.of("email","audit@example.test","password","AuditOnly_493825!","displayName","Audit"));token=auth.path("accessToken").asText();owner=auth.path("user").path("id").asText();
   for(String message:List.of("siemanko","kim jesteś?","Jaka pogoda dziś w Warszawie?","Podaj pogodę na dziś w Warszawie, kurs dolara i bitcoina oraz jak sobie radzi CSS na giełdzie dzisiaj i jaki mają kurs z ostatnich 6 miesięcy")){
    String conversation="audit-"+UUID.randomUUID();Instant began=Instant.now();List<JsonNode> frames=new ArrayList<>();JsonNode response;
    if(message.equals("siemanko")||message.equals("kim jesteś?")){
     var prepared=request(HttpMethod.POST,"/api/v1/chat/stream",Map.of("conversationId",conversation,"message",message));var headers=new HttpHeaders();headers.setBearerAuth(token);headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM));
     var sse=http.exchange("/api/v1/chat/stream?token="+prepared.path("token").asText(),HttpMethod.GET,new HttpEntity<>(headers),String.class);StringBuilder answer=new StringBuilder();for(String line:sse.getBody().split("\\r?\\n")){if(line.startsWith("data:")){var frame=json.readTree(line.substring(5));frames.add(frame);if(frame.path("event").asText().equals("ANSWER_TOKEN"))answer.append(frame.path("metadata").path("text").asText());}}
     response=json.valueToTree(Map.of("response",answer.toString()));
    }else response=request(HttpMethod.POST,"/api/v1/chat",Map.of("conversationId",conversation,"message",message));
    var row=new LinkedHashMap<String,Object>();row.put("request",message);row.put("conversationId",conversation);row.put("startedAt",began.toString());row.put("ingressFinishedAt",Instant.now().toString());row.put("response",response);row.put("uiEvents",frames);report.add(row);
    if(message.startsWith("Podaj")){
     var background=request(HttpMethod.POST,"/api/v1/chat",Map.of("conversationId","parallel-"+UUID.randomUUID(),"message","Cześć"));row.put("conversationDuringWorkers",background);
     List<Map<String,Object>> snapshots=new ArrayList<>();long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(180);while(System.nanoTime()<until&&tasks.list(owner).stream().anyMatch(t->!Set.of("COMPLETED","PARTIALLY_COMPLETED","FAILED","CANCELLED","TIMED_OUT").contains(t.state()))){var metrics=tasks.metrics(owner);snapshots.add(Map.of("at",Instant.now().toString(),"activeWorkers",metrics.get("activeWorkerCount"),"activeCoordinators",metrics.get("activeCoordinatorCount"),"busy",metrics.get("busy"),"inferenceSlotsInUse",metrics.get("gatewaySlotsInUse")));Thread.sleep(500);}
     row.put("executionSnapshots",snapshots);
     row.put("tasks",tasks.list(owner));row.put("telemetry",store.documents(owner,"agent-telemetry","").stream().map(com.jarvis.common.companion.CompanionDocumentStore.Document::body).toList());row.put("events",tasks.replay(owner,0));row.put("metrics",tasks.metrics(owner));row.put("routingDecisions",store.documents(owner,"semantic-route-v2",conversation+"/").stream().map(com.jarvis.common.companion.CompanionDocumentStore.Document::body).toList());row.put("finishedAt",Instant.now().toString());
     long workers=tasks.list(owner).stream().filter(t->!t.role().equals("CONVERSATION")).count();row.put("actualWorkerCount",workers);row.put("fourWorkersVerified",workers==4);row.put("productionVerification","NOT_VERIFIED");row.put("marketFactsComplete",tasks.list(owner).stream().filter(t->t.role().equals("CONVERSATION")).allMatch(t->t.state().equals("COMPLETED")));
    }
    if(response.toString().contains("\\\"maxResults\\\":5,\\\"profile\\\""))throw new AssertionError("Tool argument leakage");
   }
   var cancelled=request(HttpMethod.POST,"/api/v1/companion/tasks",Map.of("message","Wykonaj szczegółowy research historycznych notowań","role","RESEARCH","conversationId","cancel-audit","parentRequestId","cancel","timeoutSeconds",60));var cancelAck=request(HttpMethod.POST,"/api/v1/companion/tasks/"+cancelled.path("taskId").asText()+"/cancel",Map.of());String cancelId=cancelled.path("taskId").asText();long cancelUntil=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);while(!Set.of("CANCELLED","FAILED","COMPLETED","TIMED_OUT").contains(tasks.get(owner,cancelId).state())&&System.nanoTime()<cancelUntil)Thread.sleep(100);report.add(Map.of("cancel",cancelAck,"terminal",tasks.get(owner,cancelId),"events",tasks.replay(owner,0)));Assertions.assertEquals("CANCELLED",tasks.get(owner,cancelId).state());
  }finally{Path target=Path.of(System.getProperty("maven.multiModuleProjectDirectory",".")).resolve("real-ingress-audit.json");Files.writeString(target,json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("model","REAL_LOCAL_OLLAMA_gpt-oss:20b","windowsUiVerified",false,"llamacppProduction","NOT_VERIFIED","cases",report)));}
 }
}
