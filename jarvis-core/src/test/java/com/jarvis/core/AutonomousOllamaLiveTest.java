package com.jarvis.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.companion.*;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.*;
import com.jarvis.common.companion.IncompleteTaskException;
import com.jarvis.common.event.*;
import com.jarvis.common.memory.*;
import com.jarvis.common.model.*;
import com.jarvis.memory.*;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.image.*;
import com.jarvis.memory.sqlite.*;
import com.jarvis.memory.companion.SQLiteCompanionDocumentStore;
import com.jarvis.ollama.*;
import com.jarvis.tools.taskplan.DynamicPlanService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in real Ollama + production provider/gateway/tasks/SQLite test; excludes the full chat pipeline. */
@EnabledIfSystemProperty(named="jarvis.live.ollama",matches="true")
class AutonomousOllamaLiveTest {
    @TempDir Path temp;
    @Test void realModelDeliversThreeResultsWithOneInferenceSlotAndInteractivePriority()throws Exception{
        var mapper=new ObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);var bus=mock(CognitiveEventBus.class);
        var gate=new OllamaRequestCoordinator(true,bus,1,0,32,0,180000);
        var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("live-test",Map.of(
            "jarvis.inference.physical-context-window",4096,"jarvis.inference.profiles.planning.input-tokens",2048,
            "jarvis.inference.profiles.planning.output-tokens",768,"jarvis.inference.profiles.main-conversation.output-tokens",512,
            "jarvis.inference.profiles.planning.reasoning","LOW","jarvis.inference.profiles.main-conversation.reasoning","LOW")));
        var properties=new OllamaProperties("http://127.0.0.1:11434","gpt-oss:20b","5m",true,Set.of());
        var budgets=new ContextBudgetService(new AiContextProperties(4096,768,0),new InferenceProfileRegistry(env),new ModelMetadataResolver(HttpClient.newHttpClient(),mapper,properties));
        var capabilities=mock(ActiveModelService.class);when(capabilities.activeModelCapabilities()).thenReturn(Set.of(ModelCapability.THINKING));
        var provider=new OllamaProvider(HttpClient.newHttpClient(),mapper,properties,bus,gate,new ModelWarmupRegistry(new ModelStartupProperties()),budgets,new QwenThinkingBudgetProperties(),capabilities);
        var brain=new Brain(BrainType.FAST,"ollama","gpt-oss:20b","Live integration","",0L,ReasoningLevel.LOW);
        Set<String> contexts=ConcurrentHashMap.newKeySet();
        ChatService chat=new ChatService(){
            public ChatResponse chat(ChatRequest request){
                contexts.add(request.conversationId());
                var response=provider.toolChat(brain,List.of(ModelMessage.system("Odpowiadaj wyłącznie po polsku. Przygotuj kompletny, krótki materiał, maksymalnie 100 słów, z jednym konkretnym przykładem. Bez deklaracji przyszłej pracy."),ModelMessage.user(request.message())),List.of(),AIJobType.MAIN_MODEL);
                if(response.content().isBlank()||Set.of("length","max_tokens").contains(response.finishReason()))throw new IncompleteTaskException("LIVE_INCOMPLETE",response.content());
                return new ChatResponse(response.content());
            }
            public void stream(ChatRequest request,Consumer<CognitiveEvent> sink){throw new UnsupportedOperationException();}
        };
        var memoryProperties=new MemoryProperties(temp.resolve("memory.db").toString(),20,null,null,null,null);
        var factory=new SQLiteConnectionFactory(memoryProperties);new SQLiteMemoryInitializer(factory).afterPropertiesSet();
        var conversations=new SQLiteConversationRepository(factory);var messages=new SQLiteConversationMessageRepository(factory);
        var memory=new SQLiteConversationMemoryService(new SQLiteWorkingMemoryStore(factory,memoryProperties),conversations,messages,new SQLiteConversationImageRegistry(factory,new ConversationImageProperties(true,null,0,0,null)));
        CurrentUserContext.runAs("live-test",()->conversations.createIfAbsent("source",Instant.now()));
        long started=System.nanoTime();String[] interactive={null};
        try(var store=new SQLiteCompanionDocumentStore(true,temp.resolve("tasks.db").toString());var tasks=new CompanionTasks(chat,3)){
            tasks.configureHistory(store,mapper,new DynamicPlanService(store,mapper),30);
            var resultDelivery=new TaskResultDelivery(memory,conversations);var router=mock(com.jarvis.brain.BrainRouter.class);when(router.select(any(ChatRequest.class))).thenReturn(brain);resultDelivery.configureSynthesis(List.of(provider),router);
            ReflectionTestUtils.setField(tasks,"resultDelivery",resultDelivery);
            var adapter=new CompanionTaskSubmissionAdapter(tasks);
            for(String goal:List.of("Wyjaśnij zmienne i pętlę for w Java.","Wyjaśnij utworzenie użytkownika bez uprawnień root w Ubuntu Server.","Wyjaśnij kwantyzację modeli AI i jej wpływ na pamięć GPU."))adapter.submit("live-test","source",goal,"TASK","three-live");
            long queueDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(contexts.size()<3&&System.nanoTime()<queueDeadline)Thread.sleep(20);
            assertEquals(3,contexts.size());
            long interactiveStarted=System.nanoTime();CurrentUserContext.runAs("live-test",()->interactive[0]=chat.chat(new ChatRequest("source","Napisz krótko, że możemy nadal rozmawiać.")).response());
            long interactiveMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-interactiveStarted);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            var persisted=new ArrayList<ConversationMessage>();
            while(System.nanoTime()<end){persisted.clear();CurrentUserContext.runAs("live-test",()->persisted.addAll(messages.getAllMessages("source")));if(persisted.size()==4)break;Thread.sleep(100);}
            assertEquals(4,persisted.size(),tasks.list("live-test").toString());
            assertTrue(tasks.list("live-test").stream().allMatch(t->t.state().equals("COMPLETED")&&t.result().length()>120));
            assertEquals(4,persisted.stream().filter(m->m.role()==MessageRole.ASSISTANT).count());assertFalse(interactive[0].isBlank());
            var report=new LinkedHashMap<String,Object>();report.put("model","gpt-oss:20b");report.put("inferenceSlots",1);report.put("taskWorkers",3);report.put("interactiveMs",interactiveMs);report.put("totalMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));report.put("interactive",interactive[0]);report.put("tasks",tasks.list("live-test"));report.put("messages",persisted);
            Path root=Path.of("").toAbsolutePath();while(!Files.isDirectory(root.resolve("SHARED"))&&root.getParent()!=null)root=root.getParent();
            Files.writeString(root.resolve("SHARED/AUTONOMOUS_OLLAMA_LIVE_RESULTS.json"),mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
