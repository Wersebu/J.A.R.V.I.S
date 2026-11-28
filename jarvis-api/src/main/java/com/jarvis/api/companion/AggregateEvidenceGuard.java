package com.jarvis.api.companion;

import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.AIProviderException;
import java.util.*;
import java.util.regex.Pattern;

/** A failed market lookup does not establish a venue, even if a worker's name guessed one. */
final class AggregateEvidenceGuard {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Pattern VENUE=Pattern.compile("(?iu)\\b(?:NASDAQ|NYSE|GPW|XETRA|EURONEXT|BSE|NSE|LSE|TSX|ASX|HKEX)\\b");
    private static JsonNode outcomes(String evidence){try{var value=JSON.readTree(evidence);return value!=null&&value.path("outcomes").isArray()?value.path("outcomes"):null;}catch(Exception e){return null;}}
    static boolean incomplete(String evidence){var items=outcomes(evidence);if(items==null)return true;for(var item:items)if(!item.path("state").asText().equals("COMPLETED"))return true;return false;}
    static void requireGroundedVenues(String answer,String evidence,String request){
        if(!incomplete(evidence))return;
        var confirmed=new StringBuilder(Objects.toString(request,""));
        var items=outcomes(evidence);
        // Legacy task continuations supply plain evidence rather than the newer outcome envelope.
        if(items==null)confirmed.append('\n').append(evidence);
        else for(var item:items)if(item.path("state").asText().equals("COMPLETED"))confirmed.append('\n').append(item.path("result").asText());
        var source=confirmed.toString().toUpperCase(Locale.ROOT);var matcher=VENUE.matcher(answer);
        while(matcher.find())if(!Pattern.compile("\\b"+Pattern.quote(matcher.group().toUpperCase(Locale.ROOT))+"\\b").matcher(source).find())throw new AIProviderException("INVALID_FINAL_ANSWER: venue was not established by available evidence");
    }
}
