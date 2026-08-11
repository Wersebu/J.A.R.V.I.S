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

        Method method = ToolCallingStage.class.getDeclaredMethod("fallbackWebSearchAnswer", ToolResult.class);
        method.setAccessible(true);

        String answer = (String) method.invoke(stage, result);

        assertThat(answer).contains("2 999,00 zł");
        assertThat(answer).doesNotContain("Web search finished");
    }
}
