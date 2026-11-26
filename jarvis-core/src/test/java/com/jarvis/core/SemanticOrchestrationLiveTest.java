package com.jarvis.core;

import com.fasterxml.jackson.databind.*;
import com.jarvis.api.companion.*;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.*;
import com.jarvis.common.dto.*;
import com.jarvis.common.event.*;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.common.memory.*;
import com.jarvis.common.model.*;
import com.jarvis.memory.*;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.conversation.DefaultConversationTitleService;
import com.jarvis.memory.image.*;
import com.jarvis.memory.sqlite.*;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import com.jarvis.ollama.*;
import com.jarvis.tools.*;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.runtime.*;
import com.jarvis.tools.schema.*;
import com.jarvis.tools.taskplan.DynamicPlanService;
import com.jarvis.tools.web.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real model decisions and native execution. HTTP fixtures are explicitly NOT live market/mail data. */
@EnabledIfSystemProperty(named="jarvis.live.semantic",matches="true")
class SemanticOrchestrationLiveTest {
    @TempDir Path temp;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final List<Map<String,Object>> report=new CopyOnWriteArrayList<>();
    private final List<Map<String,Object>> executions=new CopyOnWriteArrayList<>();
    private final AtomicInteger active=new AtomicInteger(),maximum=new AtomicInteger(),redirects=new AtomicInteger();
    private final AtomicBoolean slow=new AtomicBoolean();
    private static final String OWNER="semantic-live";
    private SQLiteCompanionDocumentStore store;
    private CompanionTasks tasks;
    private SemanticOrchestrator orchestrator;
    private DefaultConversationTitleService titles;
    private SQLiteConversationMemoryService memory;
    private SQLiteConversationRepository conversations;
    private SQLiteConversationMessageRepository messages;
    private AIProvider provider;
    private Brain brain;
    private NativeToolSchemaMapper schemas;
    private ToolRegistry registry;
    private NativeToolLoopService loop;
    private HttpServer http;
    private final AtomicReference<CompanionTasks> current=new AtomicReference<>();
    private CognitiveEventBus bus;

    private void setup()throws Exception{
        bus=new CognitiveEventBus(){public void startRequest(String a,String b,Consumer<CognitiveEvent> c){}public void finishRequest(){}public void updateBrain(BrainType a,String b){}public void publish(CognitiveEventType event,String status,String message,String node,Map<String,Object> data){if(event==CognitiveEventType.TOKEN_USAGE)AgentScope.usage(data);}};
        var gateway=new OllamaRequestCoordinator(true,bus,1,0,64,0,240000);
        var env=new StandardEnvironment();var properties=new HashMap<String,Object>();int physical=Integer.getInteger("jarvis.live.semantic.physical-context",8192);properties.put("jarvis.inference.physical-context-window",physical);
        for(String profile:List.of("planning","research","orchestration","main-conversation","tool-result-processing")){properties.put("jarvis.inference.profiles."+profile+".input-tokens",6000);properties.put("jarvis.inference.profiles."+profile+".output-tokens",1536);properties.put("jarvis.inference.profiles."+profile+".reasoning","LOW");}
        properties.put("jarvis.inference.profiles.orchestration.output-tokens",1536);properties.put("jarvis.inference.profiles.orchestration.timeout-seconds",60);
        properties.put("jarvis.inference.profiles.conversation-title.priority",30);env.getPropertySources().addFirst(new MapPropertySource("semantic-live",properties));
        var config=new OllamaProperties(System.getProperty("jarvis.live.semantic.base-url","http://127.0.0.1:11434"),"gpt-oss:20b","5m",true,Set.of());
        var budgets=new ContextBudgetService(new AiContextProperties(physical,1536,0),new InferenceProfileRegistry(env),new ModelMetadataResolver(HttpClient.newHttpClient(),mapper,config));
        var model=mock(ActiveModelService.class);when(model.activeModelCapabilities()).thenReturn(Set.of(ModelCapability.THINKING));
        provider=new OllamaProvider(HttpClient.newHttpClient(),mapper,config,bus,gateway,new ModelWarmupRegistry(new ModelStartupProperties()),budgets,new QwenThinkingBudgetProperties(),model);
        brain=new Brain(BrainType.FAST,"ollama","gpt-oss:20b","Real semantic integration","",0,ReasoningLevel.LOW);
        var mp=new MemoryProperties(temp.resolve("memory.db").toString(),20,null,null,null,null);var factory=new SQLiteConnectionFactory(mp);new SQLiteMemoryInitializer(factory).afterPropertiesSet();
        conversations=new SQLiteConversationRepository(factory);messages=new SQLiteConversationMessageRepository(factory);memory=new SQLiteConversationMemoryService(new SQLiteWorkingMemoryStore(factory,mp),conversations,messages,new SQLiteConversationImageRegistry(factory,new ConversationImageProperties(true,null,0,0,null)));
        http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);http.setExecutor(Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;}));
        http.createContext("/redirect",exchange->{redirects.incrementAndGet();exchange.getResponseHeaders().add("Location","/rate");exchange.sendResponseHeaders(301,-1);exchange.close();});
        http.createContext("/rate",exchange->{try{if(slow.get())Thread.sleep(800);}catch(InterruptedException e){Thread.currentThread().interrupt();}byte[] body="<html><title>TEST FIXTURE</title><p>USD/PLN: 3.91. Controlled fixture, not a live market quote.</p></html>".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});
        http.createContext("/weather",exchange->{byte[] body="<html><title>TEST FIXTURE</title><p>Warszawa: jutro 8 C, zachmurzenie. Controlled weather fixture.</p></html>".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});http.start();
        var definitions=List.of(definition("fx","Retrieve current currency exchange rates, including USD to PLN","QUOTE"),definition("weather","Retrieve weather forecasts for a location and date","FORECAST"),definition("mail","Read mailbox messages and identify unfinished tasks; availability must be checked","REVIEW"));
        registry=new ToolRegistry(){public List<ToolDefinition> definitions(){return definitions;}public String promptSection(){return "";}};schemas=new NativeToolSchemaMapper(registry);
        var reader=new HttpWebPageReader(new WebSearchProperties(true,"http://127.0.0.1",5,5,5,2,2,1,10,1000,12000,Duration.ofSeconds(3),Duration.ofSeconds(5))){@Override protected boolean allowPrivateHosts(){return true;}};
        ToolManager manager=new ToolManager(){public List<JarvisTool> listTools(){return List.of();}public Optional<JarvisTool> findTool(String name){return Optional.of(new JarvisTool(){public String getName(){return name;}public String getDescription(){return "Integration fixture";}public ToolResult execute(ToolRequest r){return executeFixture(r,reader);}});}public ToolResult execute(ToolRequest request){return executeFixture(request,reader);}};
        loop=new NativeToolLoopService(List.of(provider),manager,q->ToolIntent.NO_TOOL,new ToolRuntimeProperties(true,8,8,2,60,"native"),bus,new ToolRuntimeDebugService(),mapper,schemas,new StoreAuditDatasetService(bus));
        ReflectionTestUtils.setField(loop,"inferenceContextPolicy",budgets);var outputs=new ToolOutputStore();loop.setToolOutputStore(outputs);
        openEngine();outputs.configurePersistence(store);
        titles=new DefaultConversationTitleService(conversations,messages,List.of(provider),bus);var beans=new StaticListableBeanFactory();beans.addBean("tasks",tasks);ReflectionTestUtils.setField(titles,"journal",beans.getBeanProvider(CompanionEventSink.class));
    }
    private ToolDefinition definition(String name,String description,String operation){return new ToolDefinition(name,description,List.of(new ToolOperationDefinition(operation,description,List.of(new ToolArgumentDefinition("query","string",false,"Location, date or lookup details")),false,ToolSafetyLevel.READ)));}
    private ToolResult executeFixture(ToolRequest request,HttpWebPageReader reader){
        assertTrue(AgentScope.permits(request.toolName(),request.operation()));int count=active.incrementAndGet();maximum.accumulateAndGet(count,Math::max);long started=System.nanoTime();
        try{
            executions.add(Map.of("taskId",TaskExecutionContext.current(),"tool",request.toolName(),"operation",request.operation(),"at",Instant.now().toString()));
            if(request.toolName().equals("mail"))return new ToolResult(false,"mail",request.operation(),request.requestId(),request.conversationId(),false,List.of(),"Integration unavailable: fixture has no mailbox credentials",Map.of("available",false),"INTEGRATION_UNAVAILABLE","Report unavailable integration; do not invent messages",false,"");
            var page=reader.read("http://127.0.0.1:"+http.getAddress().getPort()+(request.toolName().equals("fx")?"/redirect":"/weather"));
            return new ToolResult(true,request.toolName(),request.operation(),request.requestId(),request.conversationId(),false,List.of(),"Actual HTTP fixture read",Map.of("content",page.text(),"url",page.url(),"fixture",true),"","",false,"");
        }finally{active.decrementAndGet();}
    }
    private ChatService worker(){return new ChatService(){public ChatResponse chat(ChatRequest request){
        boolean external=AgentScope.current().tools().stream().anyMatch(t->t.startsWith("fx__")||t.startsWith("weather__")||t.startsWith("mail__"));
        if(!external){var response=provider.toolChat(brain,List.of(ModelMessage.system("Wykonaj analizę na podstawie dołączonych wyników zależności. Odpowiedz krótko po polsku. Nie wymyślaj danych."),ModelMessage.user(request.message())),List.of(),AIJobType.MAIN_MODEL);return new ChatResponse(response.content());}
        var result=loop.execute(new ToolCallingRequest(UUID.randomUUID().toString(),request.conversationId(),request.message(),request.message(),"Execute the semantically assigned goal",Map.of("semanticOrchestration",true),"Use the assigned integration for current facts. If unavailable, report the failure. Return a short grounded answer in Polish.",brain,KnowledgeMode.FAST,List.of()));
        if(result.results().stream().noneMatch(ToolResult::success))throw new IncompleteTaskException(result.results().stream().map(ToolResult::errorCode).filter(c->c!=null&&!c.isBlank()).findFirst().orElse("NO_EXECUTION_EVIDENCE"),null);if(!result.terminationInfo().completed())throw new IncompleteTaskException("INCOMPLETE_AGENT",result.finalAnswer());return new ChatResponse(result.finalAnswer());
    }public void stream(ChatRequest request,Consumer<CognitiveEvent> sink){throw new UnsupportedOperationException();}};}
    private void openEngine(){
        store=new SQLiteCompanionDocumentStore(true,temp.resolve("tasks.db").toString());tasks=new CompanionTasks(worker(),3);tasks.configureHistory(store,mapper,new DynamicPlanService(store,mapper),30);
        var delivery=new TaskResultDelivery(memory,conversations);var router=mock(com.jarvis.brain.BrainRouter.class);when(router.select(any(ChatRequest.class))).thenReturn(brain);delivery.configureSynthesis(List.of(provider),router);ReflectionTestUtils.setField(tasks,"resultDelivery",delivery);
        orchestrator=new SemanticOrchestrator(List.of(provider),schemas,registry,tasks,store,mapper,true);current.set(tasks);
    }
    private AutonomousOrchestration.Outcome start(String conversation,String text,boolean title){
        var result=new AtomicReference<AutonomousOrchestration.Outcome>();CurrentUserContext.runAs(OWNER,()->{memory.addMessage(conversation,ConversationMessage.chat(conversation,UUID.randomUUID().toString(),MessageRole.USER,text,Instant.now()));if(title)titles.maybeGenerateTitleAsync(conversation,brain);result.set(orchestrator.orchestrate(OWNER,conversation,UUID.randomUUID().toString(),text,brain));});return result.get();
    }
    private Map<String,Object> awaitCase(String name,String conversation,AutonomousOrchestration.Outcome outcome,int expected,long started)throws Exception{
        String root=outcome.rootTaskId();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(240);
        if(root!=null)while(!Set.of("COMPLETED","PARTIALLY_COMPLETED","FAILED","CANCELLED").contains(tasks.get(OWNER,root).state())&&System.nanoTime()<deadline)Thread.sleep(100);
        var workers=tasks.list(OWNER).stream().filter(t->t.conversationId()!=null&&t.conversationId().equals(conversation)&&!Boolean.TRUE.equals(tasks.info(OWNER,t.taskId()).get("coordinator"))).toList();
        var row=new LinkedHashMap<String,Object>();row.put("case",name);row.put("conversation",conversation);row.put("action",outcome.action());row.put("agents",workers.size());row.put("expectedAgents",expected);row.put("durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));row.put("inferenceSlots",1);row.put("taskWorkers",3);row.put("tasks",workers.stream().map(t->tasks.wire(OWNER,t)).toList());row.put("root",root==null?Map.of():tasks.wire(OWNER,tasks.get(OWNER,root)));row.put("events",tasks.replay(OWNER,0).events());row.put("toolExecutions",List.copyOf(executions));row.put("passed",workers.size()==expected&&(root==null||!Set.of("RUNNING","WAITING","RETRYING").contains(tasks.get(OWNER,root).state())));report.add(row);save();
        assertEquals(expected,workers.size(),row.toString());if(root!=null)assertFalse(Set.of("RUNNING","WAITING","RETRYING").contains(tasks.get(OWNER,root).state()));
        for(var worker:workers)if(!worker.state().equals("CANCELLED"))assertTrue(executions.stream().anyMatch(e->e.get("taskId").equals(worker.taskId()))||tasks.info(OWNER,worker.taskId()).get("tools").toString().contains("fx__")==false&&tasks.info(OWNER,worker.taskId()).get("tools").toString().contains("weather__")==false&&tasks.info(OWNER,worker.taskId()).get("tools").toString().contains("mail__")==false,"Worker must execute a real capability: "+worker);
        return row;
    }
    private void save()throws Exception{Path root=Path.of("").toAbsolutePath();while(!Files.isDirectory(root.resolve("SHARED")))root=root.getParent();Files.writeString(root.resolve("SHARED/SEMANTIC_ORCHESTRATION_LIVE_RESULTS.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("model","gpt-oss:20b","providerResultsMocked",false,"externalData","CONTROLLED_HTTP_FIXTURES_NOT_LIVE_MARKET_OR_MAIL","maximumConcurrentToolCalls",maximum.get(),"redirects",redirects.get(),"planningFailures",store==null?List.of():store.all("orchestration-invalid"),"cases",report)));}
    @Test void actualSemanticAgentExecutionAndRecovery()throws Exception{
        setup();try{
            String[] requests={"Cześć","Sprawdź kurs dolara","Sprawdź kurs dolara i pogodę w Warszawie","Sprawdź kurs dolara, pogodę na jutro w Warszawie i maile pod kątem niewykonanych zadań"};
            int[] counts={0,1,2,3};for(int i=0;i<requests.length;i++){final int index=i;long start=System.nanoTime();var outcome=start("baseline-"+i,requests[i],true);awaitCase("mandatory-"+(i+1),"baseline-"+i,outcome,counts[i],start);if(i>0){assertTrue(executions.stream().anyMatch(e->e.get("tool").equals("fx")&&tasks.get(OWNER,e.get("taskId").toString()).conversationId().equals("baseline-"+index)));assertEquals(i==3?"PARTIALLY_COMPLETED":"COMPLETED",tasks.get(OWNER,outcome.rootTaskId()).state());}}
            long started=System.nanoTime();var dependent=start("dependent","Najpierw sprawdź kurs dolara. Następnie w osobnym etapie na podstawie otrzymanego kursu oblicz koszt 100 USD w PLN.",true);awaitCase("dependency","dependent",dependent,2,started);assertTrue(tasks.list(OWNER).stream().filter(t->"dependent".equals(t.conversationId())).anyMatch(t->!((List<?>)tasks.info(OWNER,t.taskId()).get("dependsOn")).isEmpty()));
            assertTrue(redirects.get()>0);report.add(Map.of("case","HTTP_301","passed",true,"actualRedirects",redirects.get()));
            var failedRoot=tasks.list(OWNER).stream().filter(t->"baseline-3".equals(t.conversationId())&&Boolean.TRUE.equals(tasks.info(OWNER,t.taskId()).get("coordinator"))).findFirst().orElseThrow();assertEquals("PARTIALLY_COMPLETED",failedRoot.state());report.add(Map.of("case","integration-unavailable","passed",true,"rootState",failedRoot.state()));
            slow.set(true);started=System.nanoTime();var ongoing=start("during-title","Sprawdź kurs dolara i pogodę w Warszawie",false);CurrentUserContext.runAs(OWNER,()->{memory.addMessage("title-during-work",ConversationMessage.chat("title-during-work","title",MessageRole.USER,"Analiza serwera podczas pracy agentów",Instant.now()));titles.maybeGenerateTitleAsync("title-during-work",brain);});awaitCase("title-during-active-workers","during-title",ongoing,2,started);slow.set(false);
            var record=new AtomicReference<String>();CurrentUserContext.runAs(OWNER,()->record.set(conversations.find("title-during-work").orElseThrow().titleSource()));assertEquals("GENERATED",record.get());
            started=System.nanoTime();var restart=start("restart","Sprawdź kurs dolara i pogodę w Warszawie",false);long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(tasks.list(OWNER).stream().noneMatch(t->"restart".equals(t.conversationId())&&t.state().equals("RUNNING")&&!Boolean.TRUE.equals(tasks.info(OWNER,t.taskId()).get("coordinator")))&&System.nanoTime()<end)Thread.sleep(10);
            tasks.close();store.close();openEngine();awaitCase("restart-during-agents","restart",restart,2,started);
            assertTrue(tasks.list(OWNER).stream().filter(t->Boolean.TRUE.equals(tasks.info(OWNER,t.taskId()).get("coordinator"))).allMatch(t->Set.of("COMPLETED","FAILED","PARTIALLY_COMPLETED").contains(t.state())));report.add(Map.of("case","aggregation-terminal-statuses","passed",true));save();
        }finally{if(titles!=null)titles.close();if(tasks!=null)tasks.close();save();if(store!=null)store.close();if(http!=null)http.stop(0);}
    }
}

