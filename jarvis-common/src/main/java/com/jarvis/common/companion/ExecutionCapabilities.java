package com.jarvis.common.companion;
import java.util.*;
import java.util.function.Supplier;
/** Selected operations and declared prerequisites, enforced at the actual execution boundary. */
public final class ExecutionCapabilities {
    private static final ThreadLocal<Set<String>> CURRENT=new ThreadLocal<>();
    public static boolean permits(String tool,String operation){return CURRENT.get()==null||CURRENT.get().contains(tool.toLowerCase(Locale.ROOT)+"__"+operation.toLowerCase(Locale.ROOT));}
    public static <T>T call(Collection<String> tools,Supplier<T> work){var old=CURRENT.get();var selected=new HashSet<>(tools);if(!selected.isEmpty()){selected.add("output__read");selected.add("output__grep");}CURRENT.set(Set.copyOf(selected));try{return work.get();}finally{if(old==null)CURRENT.remove();else CURRENT.set(old);}}
}
