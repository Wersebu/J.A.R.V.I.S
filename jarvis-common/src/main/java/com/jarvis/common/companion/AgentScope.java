package com.jarvis.common.companion;
import java.util.Set;
import java.util.Map;
import com.jarvis.common.ai.RequestTokenUsage;

/** Bounded execution capabilities, not a second agent runtime. */
public final class AgentScope {
    public record Scope(String rootTaskId,String parentTaskId,int depth,Set<String> tools,boolean replaySafe) {public Scope{var selected=new java.util.HashSet<>(tools);if(!selected.isEmpty()){selected.add("output__read");selected.add("output__grep");}tools=Set.copyOf(selected);}}
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<RequestTokenUsage> USAGE=new ThreadLocal<>();
    private static final ThreadLocal<Map<String,Object>> MEASURED=new ThreadLocal<>();
    public static Scope current(){return CURRENT.get();}
    public static boolean permits(String tool,String operation){return CURRENT.get()==null||CURRENT.get().tools().contains(tool.toLowerCase()+"__"+operation.toLowerCase());}
    public static void usage(Map<String,Object> counts){if(USAGE.get()!=null)MEASURED.set(USAGE.get().record(counts));}
    public static Map<String,Object> measured(){return MEASURED.get()==null?Map.of("measured",false):MEASURED.get();}
    public static void run(Scope scope,Runnable work){var old=CURRENT.get();var oldUsage=USAGE.get();var oldMeasured=MEASURED.get();CURRENT.set(scope);USAGE.set(new RequestTokenUsage());MEASURED.remove();try{work.run();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);if(oldUsage==null)USAGE.remove();else USAGE.set(oldUsage);if(oldMeasured==null)MEASURED.remove();else MEASURED.set(oldMeasured);}}
}
