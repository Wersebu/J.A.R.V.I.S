package com.jarvis.common.ai;
/** Marks synchronous orchestration as background work without replacing provider routing. */
public final class InferencePriorityContext {
    private static final ThreadLocal<Boolean> BACKGROUND=new ThreadLocal<>();
    private InferencePriorityContext() {}
    public static AIJobType effective(AIJobType requested) { return Boolean.TRUE.equals(BACKGROUND.get()) ? AIJobType.BACKGROUND : requested; }
    public static void runBackground(Runnable work) {
        Boolean previous=BACKGROUND.get();BACKGROUND.set(true);
        try{work.run();}finally{if(previous==null)BACKGROUND.remove();else BACKGROUND.set(previous);}
    }
}
