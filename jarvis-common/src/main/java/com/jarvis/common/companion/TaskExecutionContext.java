package com.jarvis.common.companion;
public final class TaskExecutionContext {
    private static final ThreadLocal<String> TASK=new ThreadLocal<>();
    private TaskExecutionContext(){}
    public static String current(){return TASK.get();}
    public static void run(String taskId,Runnable action){String previous=TASK.get();TASK.set(taskId);try{action.run();}finally{if(previous==null)TASK.remove();else TASK.set(previous);}}
}
