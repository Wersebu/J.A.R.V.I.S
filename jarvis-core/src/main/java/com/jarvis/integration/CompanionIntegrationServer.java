package com.jarvis.integration;

import com.jarvis.api.companion.*;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.*;
import com.jarvis.common.event.CognitiveEvent;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.http.server.*;
import jakarta.servlet.http.*;
import java.util.*;
import java.util.function.Consumer;

/** Standalone nonproduction launcher: imports real companion transport, no production component scan/storage/providers. */
@Configuration
@Profile("companion-integration")
@EnableAutoConfiguration(exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class)
@EnableWebSocket
@Import({CompanionTasks.class,CompanionController.class,CompanionSocket.class,com.jarvis.memory.companion.SQLiteCompanionDocumentStore.class,com.jarvis.tools.taskplan.DynamicPlanService.class,com.jarvis.tools.taskplan.DynamicPlanTool.class,com.jarvis.tools.DefaultToolManager.class,CompanionIntegrationServer.Login.class})
public class CompanionIntegrationServer implements WebMvcConfigurer,WebSocketConfigurer {
    private final String token=required("JARVIS_INTEGRATION_TOKEN");
    private static String required(String key){String value=System.getenv(key);if(value==null||value.length()<16)throw new IllegalStateException(key+" must contain at least 16 characters");return value;}
    public static void main(String[] args){SpringApplication app=new SpringApplication(CompanionIntegrationServer.class);app.setAdditionalProfiles("companion-integration");app.setDefaultProperties(Map.of("server.address","127.0.0.1","server.port","18080","spring.application.name","jarvis-companion-INTEGRATION","spring.config.name","companion-integration","jarvis.companion.task-concurrency","2"));app.run("--server.address=127.0.0.1","--server.port=18080","--spring.config.name=companion-integration","--jarvis.companion.provider-mode=SIMULATED","--jarvis.companion.history.enabled=true","--jarvis.companion.history.path="+java.util.Optional.ofNullable(System.getenv("JARVIS_INTEGRATION_HISTORY")).orElse("./data/companion-integration/history.db"));}
    @Bean static ChatService integrationChat(com.jarvis.tools.taskplan.DynamicPlanService plans,com.jarvis.tools.ToolManager tools){return new ChatService(){
        public ChatResponse chat(ChatRequest request){try{
            if(request.message().startsWith("[SIMULATED_PLAN]")){
                String owner=CurrentUserContext.requireAuthenticatedUserId(),id=com.jarvis.common.companion.TaskExecutionContext.current();
                java.util.function.BiFunction<String,Map<String,Object>,com.jarvis.tools.ToolResult> planCall=(operation,args)->tools.execute(new com.jarvis.tools.ToolRequest("taskplan",operation,request.conversationId(),"integration-plan","","",args));
                var result=planCall.apply("CREATE",Map.of("title","[SIMULATED] Dynamic integration plan","steps",List.of("Read simulated input","Inspect simulated result")));if(!result.success())throw new IllegalStateException("Simulated plan create rejected");
                var p=plans.find(owner,id).orElseThrow();String first=p.steps().getFirst().stepId();planCall.apply("UPDATE_STEP",Map.of("expectedRevision",p.revision(),"stepId",first,"status","IN_PROGRESS"));Thread.sleep(400);
                var evidence=tools.execute(new com.jarvis.tools.ToolRequest("integration","READ",request.conversationId(),"integration-read","","",Map.of()));p=plans.find(owner,id).orElseThrow();planCall.apply("UPDATE_STEP",Map.of("expectedRevision",p.revision(),"stepId",first,"status","COMPLETED","resultSummary","[SIMULATED] Input read","evidenceRef",evidence.data().get("executionEvidenceRef")));
                p=plans.find(owner,id).orElseThrow();planCall.apply("ADD_STEP",Map.of("expectedRevision",p.revision(),"title","Verify simulated result","position",p.steps().size()));
                p=plans.find(owner,id).orElseThrow();var ids=new ArrayList<>(p.steps().stream().map(com.jarvis.tools.taskplan.DynamicPlan.Step::stepId).toList());java.util.Collections.swap(ids,1,2);planCall.apply("REORDER",Map.of("expectedRevision",p.revision(),"stepIds",ids));
                for(var step:plans.find(owner,id).orElseThrow().steps())if(step.status().equals("PENDING")){p=plans.find(owner,id).orElseThrow();planCall.apply("UPDATE_STEP",Map.of("expectedRevision",p.revision(),"stepId",step.stepId(),"status","IN_PROGRESS"));Thread.sleep(400);evidence=tools.execute(new com.jarvis.tools.ToolRequest("integration","VERIFY",request.conversationId(),"integration-verify","","",Map.of()));p=plans.find(owner,id).orElseThrow();planCall.apply("UPDATE_STEP",Map.of("expectedRevision",p.revision(),"stepId",step.stepId(),"status","COMPLETED","evidenceRef",evidence.data().get("executionEvidenceRef")));}
                p=plans.find(owner,id).orElseThrow();result=planCall.apply("COMPLETE",Map.of("expectedRevision",p.revision()));if(!result.success())throw new IllegalStateException("Simulated completion rejected");
            }else Thread.sleep(1500);
        }catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("Mock inference cancelled",ex);}return new ChatResponse("[SIMULATED] Integration task completed.");}
        public void stream(ChatRequest request,Consumer<CognitiveEvent> sink){throw new UnsupportedOperationException("Integration launcher exposes companion task transport only");}
    };}
    @Bean static com.jarvis.tools.JarvisTool integrationEvidenceTool(){return new com.jarvis.tools.JarvisTool(){
        public String getName(){return "integration";} public String getDescription(){return "[SIMULATED] Deterministic local integration fixture";}
        public com.jarvis.tools.ToolResult execute(com.jarvis.tools.ToolRequest r){return new com.jarvis.tools.ToolResult(true,"integration",r.operation(),r.requestId(),r.conversationId(),false,List.of(),"[SIMULATED] Fixture action verified",Map.of("providerMode","SIMULATED"),"","",false,"");}
    };}
    @Override public void addInterceptors(InterceptorRegistry registry){registry.addInterceptor(new HandlerInterceptor(){
        public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler)throws Exception{
            if(java.util.Set.of("/api/v1/auth/login","/api/v1/auth/bootstrap-status","/api/health").contains(request.getRequestURI()))return true;
            if(!("Bearer "+token).equals(request.getHeader("Authorization"))){response.setStatus(401);return false;}
            CurrentUserContext.set("integration-user");return true;
        }
        public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error){CurrentUserContext.clear();}
    }).addPathPatterns("/api/**");}
    @org.springframework.beans.factory.annotation.Autowired private CompanionSocket socket;
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(socket,"/ws/companion").addInterceptors(new HandshakeInterceptor(){
        public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Map<String,Object> attributes){if(!("Bearer "+token).equals(request.getHeaders().getFirst("Authorization"))){response.setStatusCode(org.springframework.http.HttpStatus.UNAUTHORIZED);return false;}attributes.put("jarvis.userId","integration-user");return true;}
        public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,WebSocketHandler handler,Exception exception){}
    });}
    @RestController static class Login {
        @GetMapping("/api/health") public Map<String,Object> health(){return Map.of("status","UP","environment","NONPRODUCTION","providerMode","SIMULATED");}
        @GetMapping("/api/v1/auth/bootstrap-status") public Map<String,Object> bootstrap(){return Map.of("bootstrapRequired",false);}
        @GetMapping("/api/v1/auth/me") public Map<String,Object> me(){return Map.of("id","integration-user","email","companion@example.test","displayName","SIMULATED integration","role","USER");}

        @PostMapping("/api/v1/auth/login") public Map<String,Object> login(@RequestBody Map<String,String> body){
            if(!"companion@example.test".equals(body.get("email")) || !required("JARVIS_INTEGRATION_PASSWORD").equals(body.get("password")))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED);
            return Map.of("accessToken",required("JARVIS_INTEGRATION_TOKEN"),"expiresAt",java.time.Instant.now().plusSeconds(3600).toString(),"user",Map.of("id","integration-user","email","companion@example.test","displayName","SIMULATED integration","role","USER"));
        }
    }
}
