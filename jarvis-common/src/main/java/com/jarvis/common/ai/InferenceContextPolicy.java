package com.jarvis.common.ai;
import java.util.List;
/** Provider-independent context policy for orchestration; does not acquire inference admission. */
public interface InferenceContextPolicy {
    record Selection(List<ModelMessage> messages,List<NativeToolDefinition> tools,int inputBudget){}
    Selection prepare(Brain brain,AIJobType type,List<ModelMessage> messages,List<NativeToolDefinition> tools);
    int inputBudget(Brain brain,AIJobType type);
}
