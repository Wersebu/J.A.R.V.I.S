package com.jarvis.common.companion;
public final class TaskExecutionContext {
    private static final ThreadLocal<String> TASK=new ThreadLocal<>();
    private static final ThreadLocal<java.util.function.BiConsumer<String,String>> STATUS=new ThreadLocal<>();
    private static final ThreadLocal<String> LANGUAGE=new ThreadLocal<>();
    private TaskExecutionContext(){}
    public static String current(){return TASK.get();}
    public static String languageReference(){return LANGUAGE.get();}
    public static void run(String taskId,java.util.function.BiConsumer<String,String> status,String languageReference,Runnable action){var previous=LANGUAGE.get();LANGUAGE.set(languageReference);try{run(taskId,status,action);}finally{if(previous==null)LANGUAGE.remove();else LANGUAGE.set(previous);}}
    public static void report(String state,String detail){var sink=STATUS.get();if(sink!=null)sink.accept(state,detail);}
    public static void run(String taskId,java.util.function.BiConsumer<String,String> status,Runnable action){var previous=STATUS.get();STATUS.set(status);try{run(taskId,action);}finally{if(previous==null)STATUS.remove();else STATUS.set(previous);}}
    public static void run(String taskId,Runnable action){String previous=TASK.get();TASK.set(taskId);try{action.run();}finally{if(previous==null)TASK.remove();else TASK.set(previous);}}
}
