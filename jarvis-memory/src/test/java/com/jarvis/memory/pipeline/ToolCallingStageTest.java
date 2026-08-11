package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.ToolCallingResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallingStageTest {

    @Test
    void fallbackWebSearchAnswerExtractsPolishZlotyPrice() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()));
        ToolResult result = new ToolResult(true, "web", "SEARCH_WEB", "request-test", "conversation-test",
                false, List.of(), "Web search finished", Map.of(
                "acceptedResults", List.of(Map.of(
                        "title", "Karty graficzne NVIDIA GeForce RTX 5060 Ti - Sklep komputerowy",
                        "snippet", "Układ: GeForce RTX 5060 Ti; Pamięć: 16 GB; Cena: 2 999,00 zł.",
                        "url", "https://example.com/rtx-5060-ti"
                ))
        ), "", "", false, "");

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackWebSearchAnswer", ToolResult.class, boolean.class);
        method.setAccessible(true);

        String answer = (String) method.invoke(stage, result, false);

        assertThat(answer).contains("2 999,00 zł");
        assertThat(answer).doesNotContain("Web search finished");
    }

    @Test
    void fallbackWebSearchAnswerReturnsUrlWhenUserAskedForLink() throws Exception {
        ToolCallingStage stage = new ToolCallingStage(request -> new ToolCallingResult(false, "", List.of(), List.of()),
                List.of(), new MainModelActionParser(new ObjectMapper()));
        ToolResult result = new ToolResult(true, "web", "SEARCH_WEB", "request-test", "conversation-test",
                false, List.of(), "Web search finished", Map.of(
                "acceptedResults", List.of(Map.of(
                        "title", "RTX 4060 Ti OLX",
                        "snippet", "Cena: 1 050 zl.",
                        "url", "https://www.olx.pl/d/oferta/rtx-4060-ti"
                ))
        ), "", "", false, "");

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackWebSearchAnswer", ToolResult.class, boolean.class);
        method.setAccessible(true);

        String answer = (String) method.invoke(stage, result, true);

        assertThat(answer).contains("https://www.olx.pl/d/oferta/rtx-4060-ti");
        assertThat(answer).doesNotContain("1 050");
        assertThat(answer).doesNotContain("Web search finished");
    }
}
