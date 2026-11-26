package com.jarvis.common.ai;

import java.util.*;
import java.util.function.Consumer;

/** Streams only an explicit user-facing JSON string from one structured model generation. */
public final class VisibleModelStream {
    private VisibleModelStream(){}
    public static ModelResponse generate(AIProvider provider,Brain brain,List<ModelMessage> messages,AIJobType job,Consumer<String> sink){
        return generate(provider,brain,messages,job,sink,metrics->{});
    }
    public static ModelResponse generate(AIProvider provider,Brain brain,List<ModelMessage> messages,AIJobType job,Consumer<String> sink,Consumer<Map<String,Object>> metrics){
        if(!provider.supportsStreamingChat())return provider.toolChat(brain,messages,List.of(),job);
        var properties=new LinkedHashMap<String,Object>();properties.put("action",Map.of("type","string","enum",List.of("DIRECT")));properties.put("answer",Map.of("type","string"));
        var schema=Map.<String,Object>of("type","object","properties",properties,"required",List.of("action","answer"),"additionalProperties",false);
        var input=new ArrayList<>(messages);input.add(ModelMessage.system("Return ONLY JSON matching the schema. Write action DIRECT FIRST, then the complete user-visible answer in answer. No private reasoning, technical arguments or wrappers inside answer."));
        long began=System.nanoTime();long[] first={-1,-1,-1};int[] chunks={0};
        var decoder=new JsonAnswerStream(text->{if(first[1]<0)first[1]=(System.nanoTime()-began)/1_000_000;sink.accept(text);if(first[2]<0)first[2]=(System.nanoTime()-began)/1_000_000;chunks[0]++;});
        var response=provider.streamChat(brain,input,List.of(),schema,job,event->{if(event.type()==ModelStreamEvent.Type.FIRST_TOKEN&&first[0]<0)first[0]=(System.nanoTime()-began)/1_000_000;if(event.type()==ModelStreamEvent.Type.CONTENT)decoder.accept(event.text());});
        metrics.accept(Map.of("timeToFirstTokenMs",first[0],"timeToFirstVisibleAnswerTokenMs",first[1],"timeToFirstSinkWriteMs",first[2],"generationCompletedMs",(System.nanoTime()-began)/1_000_000,"answerChunks",chunks[0]));
        try{
            var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.content());
            if(!parsed.path("action").asText().equals("DIRECT")||!parsed.path("answer").isTextual()||!parsed.path("answer").asText().equals(decoder.answer()))throw new IllegalStateException("Structured visible answer mismatch");
            return new ModelResponse(decoder.answer(),"",response.toolCalls(),response.finishReason(),response.usage());
        }catch(java.io.IOException failure){throw new IllegalStateException("Incomplete structured answer",failure);}
    }
}
