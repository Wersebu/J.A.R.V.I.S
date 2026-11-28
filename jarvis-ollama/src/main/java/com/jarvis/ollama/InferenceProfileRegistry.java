package com.jarvis.ollama;

import com.jarvis.common.ai.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** Logical budgets never change the physical runner's num_ctx. All values may be overridden. */
@Service
public class InferenceProfileRegistry implements org.springframework.beans.factory.InitializingBean {
    public enum Selection { RECENT, EXTRACTIVE, STRICT }
    public record Profile(String id, String model, int inputTokens, int outputTokens,
                          ReasoningLevel reasoning, int priority, long timeoutMillis,
                          boolean background, Selection selection, String resourceClass) {}
    private final Environment env;
    public InferenceProfileRegistry(Environment env) { this.env = env; }
    public int physicalContext() { return integer("physical-context-window", 24576); }
    public int maxOfferedTools(){int value=integer("max-offered-tools",16);if(value<4||value>256)throw new IllegalArgumentException("max-offered-tools must be 4..256");return value;}
    @Override public void afterPropertiesSet(){
        for(String id:java.util.List.of("orchestration","agent-execution","main-conversation","conversation-title","acknowledgment","progress","planning","research","coding","memory-extraction","memory-decision","dreaming","tool-selection","tool-result-processing","warmup"))
            InferenceProfileContext.call(id,()->resolve(AIJobType.CHAT));
        maxOfferedTools();
    }
    private int integer(String key, int fallback) { return env.getProperty("jarvis.inference." + key, Integer.class, fallback); }
    public Profile resolve(AIJobType type) {
        String id = InferenceProfileContext.current();
        if (id == null) id = type == AIJobType.MEMORY_AGENT ? "memory-extraction" :
            InferencePriorityContext.effective(type) == AIJobType.BACKGROUND ? "research" : "main-conversation";
        int input = switch (id) { case "orchestration" -> 16384; case "conversation-title" -> 2048; case "memory-extraction", "memory-decision" -> 4096;
            case "agent-execution", "planning", "research", "dreaming", "tool-selection" -> 8192; case "coding" -> 16384; case "acknowledgment", "progress" -> 1024; default -> Integer.MAX_VALUE; };
        int output = switch (id) { case "warmup" -> 16; case "conversation-title" -> 128; case "acknowledgment", "progress" -> 96;
            case "memory-extraction", "memory-decision", "dreaming", "tool-selection" -> 768; case "planning" -> 1024; case "coding" -> 4096; default -> 2048; };
        boolean background = !id.equals("orchestration")&&!id.equals("main-conversation") && !id.startsWith("tool-");
        background |= InferencePriorityContext.effective(type) == AIJobType.BACKGROUND || type == AIJobType.MEMORY_AGENT;
        String prefix = "profiles." + id + ".";
        String classification=env.getProperty("jarvis.inference."+prefix+"classification","INHERIT").toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("INHERIT","BACKGROUND","INTERACTIVE").contains(classification))throw new IllegalArgumentException("Invalid inference classification");
        if(classification.equals("BACKGROUND"))background=true;
        if(classification.equals("INTERACTIVE")&&InferencePriorityContext.effective(type)!=AIJobType.BACKGROUND&&type!=AIJobType.MEMORY_AGENT)background=false;
        int priority = integer(prefix + "priority", background ? 50 : 10);
        int timeout = integer(prefix + "timeout-seconds", id.equals("conversation-title") ? 20 : id.equals("coding")?300:180);
        input = integer(prefix + "input-tokens", input); output = integer(prefix + "output-tokens", output);
        String model = env.getProperty("jarvis.inference." + prefix + "model", "");
        ReasoningLevel reasoning = ReasoningLevel.valueOf(env.getProperty("jarvis.inference." + prefix + "reasoning",
            id.equals("main-conversation") || id.equals("agent-execution") || id.equals("orchestration") || id.equals("conversation-title") || id.startsWith("memory") || id.startsWith("tool-") ? "LOW" : id.equals("coding")?"HIGH":"MEDIUM").toUpperCase(java.util.Locale.ROOT));
        Selection selection = Selection.valueOf(env.getProperty("jarvis.inference." + prefix + "selection", "EXTRACTIVE").toUpperCase(java.util.Locale.ROOT));
        if (physicalContext() < 512 || input < 1 || output < 1 || priority < 0 || priority > 100 || timeout < 1)
            throw new IllegalArgumentException("Invalid inference profile: " + id);
        return new Profile(id, model, input, output, reasoning, priority, timeout * 1000L, background, selection,
            env.getProperty("jarvis.inference."+prefix+"resource-class",background?"BACKGROUND":"INTERACTIVE"));
    }
}
