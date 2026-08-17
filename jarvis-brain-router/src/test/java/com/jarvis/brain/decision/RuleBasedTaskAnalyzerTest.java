package com.jarvis.brain.decision;

import com.jarvis.common.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the reported router mismatch: "przygotuj grafik na sierpien" (a store
 * audit schedule request) classified as generic CONVERSATION because the creation-verb keyword set
 * only covered "stworz/napisz/wygeneruj" and the content-noun set didn't cover scheduling nouns.
 * These tests use different example phrases on purpose - the fix must generalize to the whole verb
 * (prepare/plan/organize) and noun (schedule/route/report) categories, not one exact sentence.
 */
class RuleBasedTaskAnalyzerTest {

    private final RuleBasedTaskAnalyzer analyzer = new RuleBasedTaskAnalyzer();

    @Test
    void aSchedulingPreparationRequestIsClassifiedAsContentGenerationNotPlainConversation() {
        TaskAnalysis analysis = analyzer.analyze(new ChatRequest("conversation-1", "przygotuj harmonogram wizyt na przyszly tydzien", Instant.now()));

        assertThat(analysis.taskType()).isEqualTo(TaskType.CONTENT_GENERATION);
    }

    @Test
    void aRoutePlanningRequestIsClassifiedAsContentGenerationNotPlainConversation() {
        TaskAnalysis analysis = analyzer.analyze(new ChatRequest("conversation-1", "zaplanuj trase do klientow na jutro", Instant.now()));

        assertThat(analysis.taskType()).isEqualTo(TaskType.CONTENT_GENERATION);
    }

    @Test
    void aPlainGreetingStillClassifiesAsConversation() {
        TaskAnalysis analysis = analyzer.analyze(new ChatRequest("conversation-1", "czesc", Instant.now()));

        assertThat(analysis.taskType()).isEqualTo(TaskType.CONVERSATION);
    }

    @Test
    void aGenericCreativeWritingRequestWithoutAScheduleNounStaysCreativeWriting() {
        TaskAnalysis analysis = analyzer.analyze(new ChatRequest("conversation-1", "napisz wiersz o jesieni", Instant.now()));

        assertThat(analysis.taskType()).isEqualTo(TaskType.CREATIVE_WRITING);
    }
}
