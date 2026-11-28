package com.jarvis.common.ai;
import java.util.*;
/** Engine-neutral management boundary; implementations must serialize residency transitions. */
public interface InferenceControl {
    record Selection(String configurationId,String engine,String model,Map<String,Object> parameters) {
        public Selection { parameters=parameters==null?Map.of():Map.copyOf(parameters); }
    }
    Selection selection();
    default int contextPerRequest(Selection selection){return ((Number)selection.parameters().getOrDefault("contextSize",4096)).intValue()/((Number)selection.parameters().getOrDefault("parallelSlots",1)).intValue();}

    Map<String,Object> state();
    List<Map<String,Object>> models(String engine);
    Map<String,Object> switchTo(String engine,String model,Map<String,Object> parameters);
    Map<String,Object> process(String action);
}
