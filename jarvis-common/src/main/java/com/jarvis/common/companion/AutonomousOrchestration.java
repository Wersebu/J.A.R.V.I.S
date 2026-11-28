package com.jarvis.common.companion;
import com.jarvis.common.ai.Brain;
import java.util.List;

/** Semantic decisions are model-owned; admission, validation and execution remain Core-owned. */
public interface AutonomousOrchestration {
    record Reference(String source,String role,String requestId,String text) {}
    record Context(String currentMessage,List<Reference> history,List<Reference> memory,List<Reference> agentResults,List<Reference> referenceData,String mainPrompt) {
        public Context(String currentMessage,List<Reference> history,List<Reference> memory,List<Reference> agentResults,List<Reference> referenceData){this(currentMessage,history,memory,agentResults,referenceData,"");}
        public Context {history=List.copyOf(history);memory=List.copyOf(memory);agentResults=List.copyOf(agentResults);referenceData=List.copyOf(referenceData);}
        public static Context current(String message){return new Context(message,List.of(),List.of(),List.of(),List.of());}
    }
    record Agent(String key,String name,String goal,List<String> tools,List<String> dependsOn) {
        public Agent {tools=tools==null?List.of():List.copyOf(tools);dependsOn=dependsOn==null?List.of():List.copyOf(dependsOn);}
    }
    record Decision(String action,String answer,List<Agent> agents) {
        public Decision {agents=agents==null?List.of():List.copyOf(agents);}
    }
    record Outcome(String action,String answer,String rootTaskId,List<String> tools,com.jarvis.common.ai.ModelResponse firstResponse) {
        public Outcome(String action,String answer,String rootTaskId,List<String> tools){this(action,answer,rootTaskId,tools,null);}
    }
    Outcome orchestrate(String owner,String conversation,String requestId,String message,Brain brain);
    default Outcome orchestrate(String owner,String conversation,String requestId,Context context,Brain brain){return orchestrate(owner,conversation,requestId,context.currentMessage(),brain);}
}
