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
        return generate(provider,brain,messages,job,sink,metrics,true);
    }
    public static ModelResponse generate(AIProvider provider,Brain brain,List<ModelMessage> messages,AIJobType job,Consumer<String> sink,Consumer<Map<String,Object>> metrics,boolean structuredOutput){
        if(!structuredOutput)return nativeAnswer(provider,brain,messages,job,sink,metrics);
        if(!provider.supportsStreamingChat())return provider.toolChat(brain,messages,List.of(),job);
        var properties=new LinkedHashMap<String,Object>();properties.put("action",Map.of("type","string","enum",List.of("DIRECT","ANSWER")));properties.put("answer",Map.of("type","string"));
        var schema=Map.<String,Object>of("type","object","properties",properties,"required",List.of("action","answer"),"additionalProperties",false);
        var input=new ArrayList<>(messages);input.add(ModelMessage.system("Return ONLY JSON matching the schema. Write action DIRECT FIRST, then the complete user-visible answer in answer. No private reasoning, technical arguments or wrappers inside answer."));
        long began=System.nanoTime();long[] first={-1,-1,-1};int[] chunks={0};
        var decoder=new JsonAnswerStream(text->{if(first[1]<0)first[1]=(System.nanoTime()-began)/1_000_000;sink.accept(text);if(first[2]<0)first[2]=(System.nanoTime()-began)/1_000_000;chunks[0]++;});
        var response=provider.streamChat(brain,input,List.of(),schema,job,event->{if(event.type()==ModelStreamEvent.Type.FIRST_TOKEN&&first[0]<0)first[0]=(System.nanoTime()-began)/1_000_000;if(event.type()==ModelStreamEvent.Type.CONTENT)decoder.accept(event.text());});
        metrics.accept(Map.of("timeToFirstTokenMs",first[0],"timeToFirstVisibleAnswerTokenMs",first[1],"timeToFirstSinkWriteMs",first[2],"generationCompletedMs",(System.nanoTime()-began)/1_000_000,"answerChunks",chunks[0]));
        try{
            var parsed=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.content());
            String action=parsed.path("action").asText().toUpperCase(Locale.ROOT);
            if(!Set.of("DIRECT","ANSWER").contains(action)||!parsed.path("answer").isTextual()||!parsed.path("answer").asText().equals(decoder.answer()))throw new IllegalStateException("Structured visible answer mismatch: action="+action+", parsedAnswerChars="+parsed.path("answer").asText().length()+", decodedAnswerChars="+decoder.answer().length());
            // Legacy main prompts use ANSWER. The lexer withheld that branch; publish once only
            // after the complete envelope validates, rather than simulating token streaming.
            if(!decoder.action().equals("DIRECT"))sink.accept(decoder.answer());
            return new ModelResponse(decoder.answer(),"",response.toolCalls(),response.finishReason(),response.usage());
        }catch(java.io.IOException failure){throw new IllegalStateException("Incomplete structured answer",failure);}
    }
    /** Buffered response envelope for tool-capable models without JSON-schema capability. */
    private static ModelResponse nativeAnswer(AIProvider provider,Brain brain,List<ModelMessage> messages,AIJobType job,Consumer<String> sink,Consumer<Map<String,Object>> metrics){
        String name="conversation__answer";
        var schema=Map.<String,Object>of("type","object","properties",Map.of("answer",Map.of("type","string")),"required",List.of("answer"),"additionalProperties",false);
        var input=new ArrayList<>(messages);input.add(ModelMessage.system("Return the complete user-facing answer using conversation__answer. This is a response envelope, not an executable action. Do not return operational arguments or reasoning as answer."));
        long began=System.nanoTime();var response=provider.toolChat(brain,input,List.of(new NativeToolDefinition(name,"Deliver the final answer only",schema)),job);
        if(response.toolCalls().size()!=1||!response.toolCalls().getFirst().name().equals(name)||Set.of("length","max_tokens").contains(response.finishReason()))throw new AIProviderException("INVALID_FINAL_ANSWER: missing complete native response envelope");
        Object value=response.toolCalls().getFirst().arguments().get("answer");
        if(!(value instanceof String answer)||answer.isBlank())throw new AIProviderException("INVALID_FINAL_ANSWER: empty native response envelope");
        metrics.accept(Map.of("timeToFirstTokenMs",-1,"timeToFirstVisibleAnswerTokenMs",-1,"timeToFirstSinkWriteMs",-1,"generationCompletedMs",(System.nanoTime()-began)/1_000_000,"answerChunks",0,"deliveryMode","BUFFERED_NATIVE_ENVELOPE"));
        // Caller validates the deliverable before publication. Never stream tool argument deltas.
        return new ModelResponse(answer,"",List.of(),response.finishReason(),response.usage());
    }
}
