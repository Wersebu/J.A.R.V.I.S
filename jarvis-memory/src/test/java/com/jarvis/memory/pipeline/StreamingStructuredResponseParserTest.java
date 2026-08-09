package com.jarvis.memory.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingStructuredResponseParserTest {

    @Test
    void streamsFinalAnswerBeforeJsonIsComplete() {
        StreamingStructuredResponseParser parser = new StreamingStructuredResponseParser();

        assertThat(parser.accept("{\"type\":\"FINAL_ANSWER\",\"answer\":\"Hel").detectedType())
                .contains(MainModelActionType.FINAL_ANSWER);
        assertThat(parser.streamedValue()).isEqualTo("Hel");

        StreamingStructuredResponseParser.ParserUpdate update = parser.accept("lo Damian");

        assertThat(update.streamedText()).isEqualTo("lo Damian");
        assertThat(parser.streamedValue()).isEqualTo("Hello Damian");
    }

    @Test
    void streamsClarificationQuestionBeforeJsonIsComplete() {
        StreamingStructuredResponseParser parser = new StreamingStructuredResponseParser();

        parser.accept("{\"type\":\"CLARIFICATION\",\"question\":\"Ktory");
        StreamingStructuredResponseParser.ParserUpdate update = parser.accept(" plik?");

        assertThat(parser.detectedType()).contains(MainModelActionType.CLARIFICATION);
        assertThat(update.streamedText()).isEqualTo(" plik?");
        assertThat(parser.streamedValue()).isEqualTo("Ktory plik?");
    }

    @Test
    void detectsToolRequestWithoutStreamingJson() {
        StreamingStructuredResponseParser parser = new StreamingStructuredResponseParser();

        StreamingStructuredResponseParser.ParserUpdate update = parser.accept("{\"type\":\"TOOL_REQUEST\",\"goal\":\"save knowledge\"");

        assertThat(update.detectedType()).contains(MainModelActionType.TOOL_REQUEST);
        assertThat(update.streamedText()).isEmpty();
        assertThat(parser.streamedValue()).isEmpty();
    }
}
