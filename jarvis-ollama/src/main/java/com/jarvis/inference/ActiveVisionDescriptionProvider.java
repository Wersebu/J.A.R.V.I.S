package com.jarvis.inference;
import com.jarvis.common.ai.*;
import com.jarvis.ollama.OllamaProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import java.util.*;
/** Preserves dedicated Ollama CPU vision while respecting an active llama.cpp residency. */
@Service @Primary
public class ActiveVisionDescriptionProvider implements VisionDescriptionProvider {
 private final InferenceControl control;private final ObjectProvider<OllamaProvider> ollama;private final LlamaCppInferenceEngine llama;
 public ActiveVisionDescriptionProvider(InferenceControl control,ObjectProvider<OllamaProvider> ollama,LlamaCppInferenceEngine llama){this.control=control;this.ollama=ollama;this.llama=llama;}
 public String describeImage(String model,String question,String base64,boolean forceCpu){
  var pinned=InferenceSelectionContext.current();var selected=pinned==null?control.selection():pinned;
  return InferenceSelectionContext.call(selected,()->{
   if(selected.engine().equals("ollama"))return ollama.getObject().describeImage(model,question,base64,forceCpu);
   if(!selected.engine().equals("llamacpp"))throw new AIProviderException("ENGINE_UNAVAILABLE");
   if(forceCpu&&((Number)selected.parameters().getOrDefault("gpuLayers",0)).intValue()>0)throw new AIProviderException("CPU_AUXILIARY_UNAVAILABLE: active llama.cpp uses GPU");
   Brain brain=new Brain(BrainType.CLASSIFIER,selected.engine(),selected.model(),"Vision description","",0,ReasoningLevel.LOW,selected.configurationId());
   return llama.toolChat(brain,List.of(ModelMessage.user(question,List.of(new ImageAttachment(base64,"image")))),List.of(),AIJobType.BACKGROUND).content();
  });
 }
}
