package com.jarvis.common.ai;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class FinalAnswerGuardTest {
 final List<NativeToolDefinition> tools=List.of(new NativeToolDefinition("web__search_web","",Map.of("properties",Map.of("query",Map.of("type","string"),"maxResults",Map.of("type","integer"),"profile",Map.of("type","string")),"required",List.of("query"))));
 @Test void rejectsObservedArgumentPayloadButPreservesRequestedJson(){String text="{\"maxResults\":5,\"profile\":\"CURRENT_FACT\",\"query\":\"BTC cena 28 listopada 2026\"}";assertThat(FinalAnswerGuard.toolArguments(text,tools,"Podaj cenę BTC")).isTrue();assertThat(FinalAnswerGuard.toolArguments(text,tools,"Zwróć plan wyszukiwania jako JSON")).isFalse();assertThat(FinalAnswerGuard.toolArguments("{\"temperature\":8}",tools,"Zwróć pogodę jako JSON")).isFalse();}
 @Test void nativeToolTurnsNeverBecomeFinalEvenForAJsonRequest(){assertThatThrownBy(()->FinalAnswerGuard.require(new ModelResponse("{}","",List.of(new ModelToolCall("id","web__search_web",Map.of("query","BTC"))),"tool_calls",null),tools,"Zwróć JSON")).hasMessageContaining("INVALID_FINAL_ANSWER");}
}
