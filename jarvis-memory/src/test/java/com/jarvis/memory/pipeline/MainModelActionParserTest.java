package com.jarvis.memory.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainModelActionParserTest {

    @Test
    void acceptsFinalAnswerResponseAlias() {
        MainModelActionParser parser = new MainModelActionParser(new ObjectMapper());

        MainModelAction action = parser.parse("{\"type\":\"FINAL_ANSWER\",\"response\":\"Alias dziala\"}");

        assertThat(action.type()).isEqualTo(MainModelActionType.FINAL_ANSWER);
        assertThat(action.answer()).isEqualTo("Alias dziala");
    }

    @Test
    void acceptsClarificationMessageAlias() {
        MainModelActionParser parser = new MainModelActionParser(new ObjectMapper());

        MainModelAction action = parser.parse("{\"type\":\"CLARIFICATION\",\"message\":\"Ktory plik?\"}");

        assertThat(action.type()).isEqualTo(MainModelActionType.CLARIFICATION);
        assertThat(action.question()).isEqualTo("Ktory plik?");
    }
}
