package com.jarvis.brain.decision;

import com.jarvis.common.dto.AttachmentReference;
import com.jarvis.common.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for the reported router mismatch: a short one-sentence request carrying
 * multiple attachments (e.g. photos of store lists to extract and schedule) was scored as
 * complexity=1/"Low complexity" purely because the heuristic only ever looked at message length -
 * despite genuinely needing multi-step extraction/tool work a plain conversational reply never
 * does. Attachments must raise the score, scaled and capped, never an automatic maximum.
 */
class HeuristicComplexityAnalyzerTest {

    private final HeuristicComplexityAnalyzer analyzer = new HeuristicComplexityAnalyzer();

    private static final KnowledgeAnalysis NOT_REQUIRED = new KnowledgeAnalysis(false, 0, 0, "Not required");

    @Test
    void aShortMessageWithNoAttachmentsStaysLowComplexity() {
        ChatRequest request = new ChatRequest("conversation-1", "czesc", Instant.now());
        TaskAnalysis taskAnalysis = new TaskAnalysis(TaskType.CONVERSATION, 0.9, "Short conversation");

        ComplexityScore score = analyzer.analyze(request, taskAnalysis, NOT_REQUIRED);

        assertThat(score.score()).isLessThanOrEqualTo(3);
    }

    @Test
    void multipleAttachmentsOnAShortMessageRaiseComplexityMeaningfully() {
        ChatRequest withAttachments = new ChatRequest("conversation-1", "przygotuj harmonogram wizyt", Instant.now(),
                com.jarvis.common.knowledge.KnowledgeMode.AUTO,
                List.of(new AttachmentReference("workspace-1", "att-1"), new AttachmentReference("workspace-1", "att-2")));
        ChatRequest withoutAttachments = new ChatRequest("conversation-1", "przygotuj harmonogram wizyt", Instant.now());
        TaskAnalysis taskAnalysis = new TaskAnalysis(TaskType.CONVERSATION, 0.6, "Default conversation");

        ComplexityScore withScore = analyzer.analyze(withAttachments, taskAnalysis, NOT_REQUIRED);
        ComplexityScore withoutScore = analyzer.analyze(withoutAttachments, taskAnalysis, NOT_REQUIRED);

        assertThat(withScore.score()).isGreaterThan(withoutScore.score());
        assertThat(withScore.score()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void aSingleCasualAttachmentIsNeverAutomaticallyTreatedAsMaximallyComplex() {
        ChatRequest request = new ChatRequest("conversation-1", "co jest na tym zdjeciu?", Instant.now(),
                com.jarvis.common.knowledge.KnowledgeMode.AUTO,
                List.of(new AttachmentReference("workspace-1", "att-1")));
        TaskAnalysis taskAnalysis = new TaskAnalysis(TaskType.CONVERSATION, 0.6, "Default conversation");

        ComplexityScore score = analyzer.analyze(request, taskAnalysis, NOT_REQUIRED);

        assertThat(score.score()).isLessThan(10);
    }
}
