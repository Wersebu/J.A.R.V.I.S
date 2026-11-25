package com.jarvis.common.companion;
import java.util.*;
import java.util.function.Supplier;
/** Cooperative instructions are consumed between model/tool rounds, never injected mid-generation. */
public final class TaskInstructionContext {
    private static final ThreadLocal<Supplier<List<String>>> MAILBOX=new ThreadLocal<>();
    private TaskInstructionContext(){}
    public static void run(Supplier<List<String>> mailbox,Runnable work){var old=MAILBOX.get();MAILBOX.set(mailbox);try{work.run();}finally{if(old==null)MAILBOX.remove();else MAILBOX.set(old);}}
    public static List<String> drain(){var box=MAILBOX.get();return box==null?List.of():box.get();}
}
