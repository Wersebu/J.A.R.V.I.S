package com.jarvis.common.ai;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JsonAnswerStreamTest {
    @Test void everyPossibleSplitPreservesEscapesAndUnicodeAndHidesEnvelope(){
        String raw="{\"action\":\"DIRECT\",\"answer\":\"Za\\u017cółć \\uD83D\\uDE80 \\\"cytat\\\" \\\\ \\nkoniec\",\"tools\":[]}";
        String expected="Zażółć 🚀 \"cytat\" \\ \nkoniec";
        for(int split=0;split<=raw.length();split++){
            var chunks=new ArrayList<String>();var parser=new JsonAnswerStream(chunks::add);parser.accept(raw.substring(0,split));parser.accept(raw.substring(split));
            assertEquals(expected,String.join("",chunks),"split "+split);assertEquals(expected,parser.answer());
        }
    }
    @Test void neverStreamsDelegationOrNestedToolData(){
        var chunks=new ArrayList<String>();var parser=new JsonAnswerStream(chunks::add);
        parser.accept("{\"action\":\"DELEGATE\",\"answer\":\"not a direct answer\",\"agents\":[{\"answer\":\"secret technical arguments\"}]}");assertTrue(chunks.isEmpty());
    }
    @Test void actionMustBeKnownBeforeAnyAnswerIsVisible(){
        var chunks=new ArrayList<String>();var parser=new JsonAnswerStream(chunks::add);
        parser.accept("{\"answer\":\"Visible later\",");assertTrue(chunks.isEmpty());parser.accept("\"action\":\"DIRECT\"}");assertEquals(List.of("Visible later"),chunks);
    }
    @Test void streamsBeforeTheFinalObjectCloses(){
        var chunks=new ArrayList<String>();var parser=new JsonAnswerStream(chunks::add);parser.accept("{\"action\":\"DIRECT\",\"answer\":\"first");assertEquals(List.of("first"),chunks);parser.accept(" second\"}");assertEquals(List.of("first"," second"),chunks);
    }
    @Test void duplicateKeysCannotSwitchTheVisibleAction(){
        var parser=new JsonAnswerStream(text->{});assertThrows(IllegalArgumentException.class,()->parser.accept("{\"action\":\"DIRECT\",\"answer\":\"x\",\"action\":\"TOOLS\"}"));
    }
    @Test void charSizedEscapesAreNotSentAsTechnicalStrings(){
        var chunks=new ArrayList<String>();var parser=new JsonAnswerStream(chunks::add);String raw="{\"action\":\"DIRECT\",\"answer\":\"a\\n\\uD83D\\uDE80b\"}";
        raw.chars().forEach(c->parser.accept(String.valueOf((char)c)));assertEquals("a\n🚀b",String.join("",chunks));
    }
}
