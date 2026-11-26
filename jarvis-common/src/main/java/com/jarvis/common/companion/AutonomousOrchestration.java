package com.jarvis.common.companion;
import com.jarvis.common.ai.Brain;
import java.util.List;

/** Semantic decisions are model-owned; admission, validation and execution remain Core-owned. */
public interface AutonomousOrchestration {
    record Agent(String key,String name,String goal,List<String> tools,List<String> dependsOn) {
        public Agent {tools=tools==null?List.of():List.copyOf(tools);dependsOn=dependsOn==null?List.of():List.copyOf(dependsOn);}
    }
    record Decision(String action,String answer,List<Agent> agents) {
        public Decision {agents=agents==null?List.of():List.copyOf(agents);}
    }
    record Outcome(String action,String answer,String rootTaskId,List<String> tools) {}
    Outcome orchestrate(String owner,String conversation,String requestId,String message,Brain brain);
}
