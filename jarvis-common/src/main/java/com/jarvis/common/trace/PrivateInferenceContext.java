package com.jarvis.common.trace;
import java.util.function.Supplier;
/** Prevent automatic memory extraction payloads from entering optional full-prompt diagnostics. */
public final class PrivateInferenceContext {
    private static final ThreadLocal<Boolean> PRIVATE=new ThreadLocal<>();
    private PrivateInferenceContext(){}
    public static boolean active(){return Boolean.TRUE.equals(PRIVATE.get());}
    public static <T>T call(Supplier<T> work){Boolean previous=PRIVATE.get();PRIVATE.set(true);try{return work.get();}finally{if(previous==null)PRIVATE.remove();else PRIVATE.set(previous);}}
}
