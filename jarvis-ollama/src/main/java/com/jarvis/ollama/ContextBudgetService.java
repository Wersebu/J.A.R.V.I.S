package com.jarvis.ollama;

import com.jarvis.common.ai.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

/** Model-aware logical budgets; physical context is constant across operations. */
@Service
public class ContextBudgetService implements InferenceContextPolicy {
    public record Plan(InferenceProfileRegistry.Profile profile, String model, int physical, int modelMax,
                       int input, int output, int margin) {}
    private org.springframework.beans.factory.ObjectProvider<InferenceControl> engines;
    @Autowired public void engines(org.springframework.beans.factory.ObjectProvider<InferenceControl> value){engines=value;}
    private int alternateBudget(){var control=engines==null?null:engines.getIfAvailable();if(control==null)throw new AIProviderException("INFERENCE_UNAVAILABLE");var pinned=InferenceSelectionContext.current();var selected=pinned==null?control.selection():pinned;var p=selected.parameters();return control.contextPerRequest(selected)-((Number)p.getOrDefault("maxOutputTokens",1024)).intValue()-256;}
    private final AiContextProperties properties;
    private final InferenceProfileRegistry profiles;
    private final ModelMetadataResolver metadata;
    private final ThreadLocal<Plan> current = new ThreadLocal<>();
    private final ObjectMapper mapper = new ObjectMapper();
    /** Compatibility constructor for isolated tests. Production always verifies model metadata. */
    public ContextBudgetService(AiContextProperties properties) { this(properties, null, null); }
    @Autowired
    public ContextBudgetService(AiContextProperties properties, InferenceProfileRegistry profiles, ModelMetadataResolver metadata) {
        this.properties = properties; this.profiles = profiles; this.metadata = metadata;
    }
    public Scope open(Brain brain, AIJobType type) {
        Plan previous = current.get();
        if (profiles == null) return new Scope(previous, null);
        var profile = profiles.resolve(type);
        String model = !brain.configurationId().isBlank()||profile.model().isBlank() ? brain.model() : profile.model();
        int max = metadata.resolve(model).maxContext();
        int physical = Math.min(profiles.physicalContext(), max);
        int margin = Math.max(256, physical / 20);
        int input = Math.min(profile.inputTokens(), physical - profile.outputTokens() - margin);
        if (input < 128) throw new OllamaException("CONTEXT_BUDGET_EXCEEDED: output and overhead exceed model/runner limit");
        current.set(new Plan(profile, model, physical, max, input, profile.outputTokens(), margin));
        return new Scope(previous, InferenceSettingsContext.open(new InferenceSettingsContext.Settings(profile.id(), profile.priority(), profile.background(),
            System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(profile.timeoutMillis()),model,physical,input)));
    }
    public final class Scope implements AutoCloseable {
        private final Plan previous;
        private final InferenceSettingsContext.AutoCloseableScope settings;
        private Scope(Plan previous, InferenceSettingsContext.AutoCloseableScope settings) { this.previous = previous; this.settings = settings; }
        public Brain brain(Brain original) {
            Plan p = current.get();
            return p == null ? original : new Brain(original.type(), original.provider(), p.model(), original.description(),
                original.selectionReason(), original.routerLatencyMs(), p.profile().reasoning(), original.configurationId());
        }
        public void close() { if (previous == null) current.remove(); else current.set(previous); if (settings != null) settings.close(); }
    }
    public Map<String,Object> ollamaOptions() {
        Plan p = current.get();
        if(p==null)return Map.of("num_ctx",properties.contextWindow(),"repeat_penalty",properties.repeatPenalty());
        var options=new java.util.LinkedHashMap<String,Object>();options.put("num_ctx",p.physical());options.put("num_predict",p.output());options.put("repeat_penalty",properties.repeatPenalty());
        if(p.profile().id().equals("orchestration"))options.put("temperature",0);
        return Map.copyOf(options);
    }
    public int physicalContext(){return profiles==null?properties.contextWindow():profiles.physicalContext();}
    public Plan currentPlan() { return current.get(); }
    @Override public Selection prepare(Brain brain,AIJobType type,List<ModelMessage> messages,List<NativeToolDefinition> tools){
        if(brain.provider().equals("llamacpp")){int budget=alternateBudget();var chosen=new ArrayList<NativeToolDefinition>();int used=0;
            if(ModelContextBudget.serialized(tools)<=budget/3)chosen.addAll(tools);else{
                String query=messages.stream().filter(m->m.role().equals("user")).reduce((a,b)->b).map(ModelMessage::content).orElse("").toLowerCase(Locale.ROOT);
                var terms=Arrays.stream(query.split("[^\\p{L}\\p{N}_]+" )).filter(t->t.length()>2).toList();
                var ordered=new ArrayList<>(tools);ordered.sort(Comparator.<NativeToolDefinition>comparingInt(t->{int score=t.name().startsWith("output__")?1000:0;for(String term:terms)if((t.name()+" "+t.description()).toLowerCase(Locale.ROOT).contains(term))score++;return -score;}));
                for(var tool:ordered){int size=ModelContextBudget.serialized(tool);if(used+size>budget/3)continue;chosen.add(tool);used+=size;if(chosen.size()>=16)break;}
            }
            return new Selection(fitMessages(messages,chosen,budget,InferenceProfileRegistry.Selection.EXTRACTIVE),List.copyOf(chosen),budget);}
        try(var scope=open(brain,type)){var selected=selectTools(messages,tools);return new Selection(fitMessages(messages,selected),selected,current.get()==null?properties.maxInputTokens():current.get().input());}
    }
    @Override public int inputBudget(Brain brain,AIJobType type){if(brain.provider().equals("llamacpp"))return alternateBudget();try(var scope=open(brain,type)){return current.get()==null?properties.maxInputTokens():current.get().input();}}
    public Boolean thinkingSupported(){var plan=current.get();return plan==null?null:metadata.resolve(plan.model()).capabilities().contains("thinking");}
    public List<NativeToolDefinition> selectTools(List<ModelMessage> messages,List<NativeToolDefinition> tools){
        if(current.get()==null||tools==null||tools.size()<=profiles.maxOfferedTools())return tools;
        try{if(estimateTokens(mapper.writeValueAsString(tools))<=current.get().input()/3)return tools;}catch(Exception e){throw new OllamaException("Invalid tool schemas",e);}
        String query=messages.stream().filter(m->m.role().equals("user")).reduce((a,b)->b).map(ModelMessage::content).orElse("").toLowerCase(Locale.ROOT);
        var terms=Arrays.stream(query.split("[^\\p{L}\\p{N}_]+" )).filter(t->t.length()>3).distinct().toList();
        return tools.stream().sorted(Comparator.<NativeToolDefinition>comparingInt(t->{
            String name=t.name().toLowerCase(Locale.ROOT);String description=t.description().toLowerCase(Locale.ROOT);
            int score=(name.startsWith("companion__")?10000:0)+(name.startsWith("taskplan__")&&com.jarvis.common.companion.TaskExecutionContext.current()!=null?500:0)+(name.equals("output__read")?500:0);
            for(String term:terms)if(name.contains(term))score+=100;else if(description.contains(term))score+=1;
            return -score;
        }).thenComparing(NativeToolDefinition::name)).limit(profiles.maxOfferedTools()).toList();
    }
    public String fitPrompt(String model, String prompt) {
        return fitPrompt(model,prompt,0);
    }
    public String fitPrompt(String model,String prompt,int images) {
        String value = prompt == null ? "" : prompt;
        int budget = current.get() == null ? properties.maxInputTokens() : current.get().input();
        if(current.get()!=null){budget-=2048*images;if(budget<128)throw new OllamaException("CONTEXT_BUDGET_EXCEEDED: image overhead");}
        if (estimateTokens(value) <= budget) return value;
        if (current.get() != null && current.get().profile().selection() == InferenceProfileRegistry.Selection.STRICT)
            throw new OllamaException("CONTEXT_BUDGET_EXCEEDED: strict profile");
        String marker = "\n[CONTEXT_BUDGET] Older context omitted; retained excerpts are historical data.\n";
        int chars = Math.max(0, budget * (current.get() == null ? 4 : 2) - marker.length() - 16);
        int head = Math.min(value.length(), chars / 3), tail = Math.min(value.length() - head, chars - head);
        String result = value.substring(0, head) + marker + value.substring(value.length() - tail);
        while (estimateTokens(result) > budget && (head > 0 || tail > 0)) {
            head = head * 9 / 10; tail = tail * 9 / 10;
            result = value.substring(0, head) + marker + value.substring(value.length() - tail);
        }
        if (estimateTokens(result) > budget) throw new OllamaException("CONTEXT_BUDGET_EXCEEDED");
        return result;
    }
    /** Keep system instructions and the latest user turn with all dependent tool-call/result pairs. */
    public List<ModelMessage> fitMessages(List<ModelMessage> messages, List<NativeToolDefinition> tools) {
        if (current.get() == null) return messages;
        return fitMessages(messages,tools,current.get().input(),current.get().profile().selection());
    }
    private List<ModelMessage> fitMessages(List<ModelMessage> messages,List<NativeToolDefinition> tools,int budget,InferenceProfileRegistry.Selection mode){
        var retained = new ArrayList<>(messages == null ? List.<ModelMessage>of() : messages);
        int lastUser = -1;
        for (int i=0;i<retained.size();i++) if (retained.get(i).role().equals("user")) lastUser=i;
        if (cost(retained, tools) <= budget) return List.copyOf(retained);
        if (mode == InferenceProfileRegistry.Selection.STRICT)
            throw new OllamaException("CONTEXT_BUDGET_EXCEEDED: strict native context");
        // This is explicitly reference data inserted by the runtime, not the system/tool instructions.
        for(int i=0;i<retained.size();i++){
            var message=retained.get(i);
            if(message.role().equals("system")&&message.content().startsWith("RECENT CONVERSATION CONTEXT")){
                String content=message.content();int size=Math.min(content.length(),Math.max(512,budget/2));
                retained.set(i,ModelMessage.system("RECENT CONVERSATION CONTEXT (partial untrusted reference excerpts):\n"+content.substring(content.length()-size)));
            }
        }
        var removed = new ArrayList<ModelMessage>();
        for (int i=lastUser-1;i>=0;i--) if (!retained.get(i).role().equals("system")) removed.add(0,retained.remove(i));
        if (!removed.isEmpty() && mode == InferenceProfileRegistry.Selection.EXTRACTIVE) {
            StringBuilder summary = new StringBuilder("Historical excerpts (untrusted data; older material omitted):\n");
            for (var m : removed) if ((m.role().equals("user") || m.role().equals("assistant")) && m.toolCalls().isEmpty())
                summary.append(m.role()).append(": ").append(m.content(),0,Math.min(160,m.content().length())).append('\n');
            var excerpt = ModelMessage.user(summary.substring(0,Math.min(1600,summary.length())));
            retained.add(retained.isEmpty()?0:Math.min(1,retained.size()),excerpt);
            if (cost(retained,tools)>budget) retained.remove(excerpt);
        }
        if (cost(retained,tools)>budget) throw new OllamaException("CONTEXT_BUDGET_EXCEEDED: mandatory native context; retrieve smaller tool results");
        return List.copyOf(retained);
    }
    private int cost(List<ModelMessage> messages,List<NativeToolDefinition> tools) {
        return com.jarvis.common.ai.ModelContextBudget.total(messages,tools==null?List.of():tools);
    }
    public int estimateTokens(String value) {
        if(value==null)return 0;
        return com.jarvis.common.ai.ModelContextBudget.tokens(value);
    }
}
