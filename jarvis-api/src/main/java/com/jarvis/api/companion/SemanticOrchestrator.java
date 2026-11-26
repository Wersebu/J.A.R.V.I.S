package com.jarvis.api.companion;

import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import com.jarvis.common.companion.*;
import com.jarvis.tools.runtime.*;
import com.jarvis.tools.schema.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** One bounded semantic planning turn, followed by the existing durable execution engine. */
@Service
public class SemanticOrchestrator implements AutonomousOrchestration {
    private final List<AIProvider> providers;
    private final NativeToolSchemaMapper schemas;
    private final ToolRegistry registry;
    private final CompanionTasks tasks;
    private final CompanionDocumentStore store;
    private final ObjectMapper mapper;
    private final boolean enabled;
    public SemanticOrchestrator(List<AIProvider> providers,NativeToolSchemaMapper schemas,ToolRegistry registry,CompanionTasks tasks,
            CompanionDocumentStore store,ObjectMapper mapper,@Value("${jarvis.orchestration.enabled:true}") boolean enabled){
        this.providers=List.copyOf(providers);this.schemas=schemas;this.registry=registry;this.tasks=tasks;this.store=store;this.mapper=mapper;this.enabled=enabled;
    }
    public Outcome orchestrate(String owner,String conversation,String requestId,String message,Brain brain){
        if(!enabled)return new Outcome("LEGACY","",null,List.of());
        String decisionKey=conversation+"/"+requestId;
        var catalog=schemas.definitions(ToolIntent.NO_TOOL);
        var readOnly=new HashSet<String>();for(var tool:registry.definitions())for(var operation:tool.operations())if(!operation.write()&&operation.safetyLevel()==ToolSafetyLevel.READ)readOnly.add(tool.name().toLowerCase()+"__"+operation.name().toLowerCase());
        var available=catalog.stream().collect(java.util.stream.Collectors.toMap(NativeToolDefinition::name,d->d,(a,b)->a,LinkedHashMap::new));
        JsonNode decision=store.find(owner,"orchestration-decision",decisionKey).map(d->read(d.body())).orElse(null);
        if(decision==null){
            StringBuilder capabilities=new StringBuilder();
            for(var entry:catalog)capabilities.append(entry.name()).append(readOnly.contains(entry.name())?" [READ_ONLY]: ":" [ACTION]: ").append(entry.description(),0,Math.min(90,entry.description().length())).append('\n');
            if(capabilities.length()>28000)throw new IllegalStateException("Orchestration capability catalog exceeds bounded planning budget");
            var provider=providers.stream().filter(p->p.provider().equalsIgnoreCase(brain.provider())).findFirst().orElseThrow();
            var messages=new ArrayList<ModelMessage>();
            messages.add(ModelMessage.system("""
                You are Core's semantic orchestrator. Analyze the user's meaning, independent deliverables, dependencies and available capabilities.
                Choose DIRECT for greetings and answers needing no execution. Return a natural answer in the user's language with no worker agents.
                Choose DELEGATE for research, live information, integration lookups or substantial independent work. Create exactly one logical worker per independent user goal, even when there is only one goal. Do not combine unrelated goals. Do not split a single simple lookup into extra workers.
                TOOLS is reserved ONLY for immediate user-authorized state-changing actions, never read-only lookups. ALL retrieval/current facts/integration reads use DELEGATE, even a single simple lookup. Put state-changing capability names in tools for TOOLS.
                For DELEGATE give each worker a unique short key, meaningful name in the user's language, self-contained goal, the MINIMAL exact tools it needs, and dependsOn containing earlier keys only. Shared context is not the whole conversation. Missing credentials/integration availability must be discovered and reported by that worker; other workers must still run. Do not invent results.
                Only select existing capability names from the catalog. Include alternative read/search capabilities when useful for source recovery. Set kind to RETRIEVAL for external facts/integration data and ANALYSIS for writing/calculation based on supplied data. RETRIEVAL MUST include at least one applicable capability. ANALYSIS must not claim to retrieve current facts. Empty tools is allowed ONLY for ANALYSIS. Never delegate external mutations speculatively.
                Every agent object MUST contain kind, key, name, goal, tools and dependsOn, including empty arrays where appropriate. Decide semantically; do not rely on explicit requests for agents or a fixed number. When execution discovers new independent work a worker may delegate an additional bounded helper. Do not disclose hidden reasoning. Return operational fields only using orchestration__decide.
                """));
            messages.add(ModelMessage.user("AVAILABLE CAPABILITIES (names and descriptions, not execution results):\n"+capabilities+"\nUSER REQUEST:\n"+message));
            RuntimeException last=null;boolean reviewed=false;long inputTokens=0,outputTokens=0,modelCalls=0;
            for(int attempt=0;attempt<3;attempt++){
                try{
                    ModelResponse response=InferenceProfileContext.call("orchestration",()->provider.toolChat(brain,messages,List.of(definition()),AIJobType.MAIN_MODEL));
                    inputTokens+=response.usage().promptTokens();outputTokens+=response.usage().completionTokens();modelCalls++;
                    if(Set.of("length","max_tokens").contains(response.finishReason()))throw new IllegalArgumentException("Incomplete orchestration generation");
                    decision=response.hasToolCalls()?mapper.valueToTree(response.toolCalls().stream().filter(t->t.name().equals("orchestration__decide")).findFirst().orElseThrow().arguments()):read(response.content());
                    validate(decision,available.keySet(),readOnly);
                    if(decision.path("action").asText().equals("DELEGATE")&&!reviewed){
                        reviewed=true;messages.add(ModelMessage.user("Perform a semantic coverage/dependency review BEFORE execution. ORIGINAL REQUEST: "+message+"\nPROPOSED PLAN (data): "+decision+"\nList every independently verifiable user deliverable as a SEPARATE worker. Never merge unrelated outcomes merely because one worker could call several tools. Retain dependencies only when one result is an actual input to another. Preserve appropriate retrieval capabilities: a retrieval worker cannot have an empty tools array. Fix omitted names/fields and preserve the user's language. Return the corrected complete orchestration__decide, without commentary."));
                        response=InferenceProfileContext.call("orchestration",()->provider.toolChat(brain,messages,List.of(definition()),AIJobType.MAIN_MODEL));
                        inputTokens+=response.usage().promptTokens();outputTokens+=response.usage().completionTokens();modelCalls++;
                        if(Set.of("length","max_tokens").contains(response.finishReason()))throw new IllegalArgumentException("Incomplete semantic plan review");
                        decision=response.hasToolCalls()?mapper.valueToTree(response.toolCalls().stream().filter(t->t.name().equals("orchestration__decide")).findFirst().orElseThrow().arguments()):read(response.content());validate(decision,available.keySet(),readOnly);
                    }
                    var usage=response.usage();
                    tasks.record(owner,"orchestration.decided",null,Map.of("conversationId",conversation,"requestId",requestId,"action",decision.path("action").asText(),"agents",decision.path("agents").size(),"inputTokens",inputTokens,"outputTokens",outputTokens,"modelCalls",modelCalls));
                    store.put(new CompanionDocumentStore.Document(owner,"orchestration-decision",decisionKey,decision.toString(),Instant.now()));break;
                }catch(RuntimeException failure){last=failure;if(decision!=null)store.put(new CompanionDocumentStore.Document(owner,"orchestration-invalid",decisionKey+"/"+attempt,decision.toString(),Instant.now()));decision=null;tasks.record(owner,"orchestration.retrying",null,Map.of("conversationId",conversation,"requestId",requestId,"attempt",attempt+1,"error",failure.getClass().getSimpleName()));messages.add(ModelMessage.system("Validation error: "+(failure instanceof IllegalArgumentException?failure.getMessage():"provider failure")+". Return a corrected COMPACT orchestration__decide. TOOLS is ONLY for state-changing actions; all reads/retrieval/current facts MUST use DELEGATE with an agents ARRAY containing one compact worker object per independent goal. Every worker MUST include ALL SIX fields: kind (RETRIEVAL or ANALYSIS), key, name, goal, tools array and dependsOn array. For RETRIEVAL choose existing applicable tools and NEVER an empty tools array. Example shape: {\"action\":\"DELEGATE\",\"answer\":\"\",\"tools\":[],\"agents\":[{\"kind\":\"RETRIEVAL\",\"key\":\"a\",\"name\":\"Task name\",\"goal\":\"Actual user goal\",\"tools\":[\"applicable__operation\"],\"dependsOn\":[]}]} DIRECT: a short answer and empty arrays. Never copy the tool catalog."));}
            }
            if(decision==null)throw new IllegalStateException("Semantic orchestration exhausted its three safe planning attempts",last);
        }
        String action=decision.path("action").asText();
        if(action.equals("DIRECT"))return new Outcome(action,decision.path("answer").asText(),null,List.of());
        if(action.equals("TOOLS"))return new Outcome(action,"",null,strings(decision.path("tools")));
        var agents=new ArrayList<Agent>();
        for(var agent:decision.path("agents"))agents.add(new Agent(agent.path("key").asText(),agent.path("name").asText(),agent.path("goal").asText(),strings(agent.path("tools")),strings(agent.path("dependsOn"))));
        var root=tasks.submitOrchestration(owner,conversation,requestId,message,agents,readOnly);
        String names=agents.stream().map(Agent::name).collect(java.util.stream.Collectors.joining(", "));
        return new Outcome(action,"Przyjęto zadania: "+names+". Wyniki pojawią się tutaj automatycznie; możemy dalej rozmawiać.",root.taskId(),List.of());
    }
    private JsonNode read(String json){try{return mapper.readTree(json);}catch(Exception e){throw new IllegalArgumentException("Invalid orchestration JSON",e);}}
    private List<String> strings(JsonNode node){if(!node.isArray())return List.of();var values=new ArrayList<String>();for(var v:node){if(!v.isTextual())throw new IllegalArgumentException("Expected capability/key strings");values.add(v.asText());}return values;}
    private void validate(JsonNode decision,Set<String> available,Set<String> readOnly){
        String action=decision.path("action").asText();if(!Set.of("DIRECT","TOOLS","DELEGATE").contains(action))throw new IllegalArgumentException("Invalid action");
        if(action.equals("DIRECT")){if(decision.path("answer").asText().isBlank())throw new IllegalArgumentException("DIRECT needs an answer");return;}
        if(action.equals("TOOLS")){if(strings(decision.path("tools")).isEmpty()||!available.containsAll(strings(decision.path("tools")))||readOnly.containsAll(strings(decision.path("tools"))))throw new IllegalArgumentException("Read-only lookups require a tracked worker; TOOLS is for mutations");return;}
        var agents=decision.path("agents");if(!agents.isArray()||agents.isEmpty()||agents.size()>32)throw new IllegalArgumentException("A plan needs 1..32 agents");
        var keys=new HashSet<String>();for(var agent:agents){String key=agent.path("key").asText();if(key.isBlank()||key.length()>80||keys.contains(key)||!keys.containsAll(strings(agent.path("dependsOn"))))throw new IllegalArgumentException("Invalid DAG");
            if(!Set.of("RETRIEVAL","ANALYSIS").contains(agent.path("kind").asText())||(agent.path("kind").asText().equals("RETRIEVAL")&&strings(agent.path("tools")).isEmpty()))throw new IllegalArgumentException("RETRIEVAL requires applicable tools; kind must be RETRIEVAL or ANALYSIS");
            if(agent.path("name").asText().isBlank()||agent.path("name").asText().length()>120||agent.path("goal").asText().isBlank()||agent.path("goal").asText().length()>16000||!available.containsAll(strings(agent.path("tools"))))throw new IllegalArgumentException("Invalid agent scope");keys.add(key);}
    }
    private NativeToolDefinition definition(){
        Map<String,Object> string=Map.of("type","string");
        var agent=Map.of("type","object","additionalProperties",false,"properties",Map.of("kind",Map.of("type","string","enum",List.of("RETRIEVAL","ANALYSIS")),"key",Map.of("type","string","maxLength",80),"name",Map.of("type","string","maxLength",120),"goal",Map.of("type","string","maxLength",2000),"tools",Map.of("type","array","items",string,"maxItems",16),"dependsOn",Map.of("type","array","items",string,"maxItems",16)),"required",List.of("kind","key","name","goal","tools","dependsOn"));
        return new NativeToolDefinition("orchestration__decide","Return one compact decision. Never explain the decision or copy the catalog.",Map.of("type","object","additionalProperties",false,"properties",Map.of("action",Map.of("type","string","enum",List.of("DIRECT","TOOLS","DELEGATE")),"answer",Map.of("type","string","maxLength",2000),"tools",Map.of("type","array","items",string,"maxItems",16),"agents",Map.of("type","array","items",agent,"maxItems",32)),"required",List.of("action","answer","tools","agents")));
    }
}
