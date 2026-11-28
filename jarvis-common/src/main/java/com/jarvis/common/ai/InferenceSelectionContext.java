package com.jarvis.common.ai;
import java.util.function.Supplier;
/** Explicit immutable internal engine assignment; callers propagate it across executor boundaries. */
public final class InferenceSelectionContext {
 private static final ThreadLocal<InferenceControl.Selection> CURRENT=new ThreadLocal<>();
 private InferenceSelectionContext(){}
 public static InferenceControl.Selection current(){return CURRENT.get();}
 public static <T>T call(InferenceControl.Selection selection,Supplier<T> action){var previous=CURRENT.get();if(selection==null)CURRENT.remove();else CURRENT.set(selection);try{return action.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}}
}
