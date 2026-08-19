package com.jarvis.tools.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GenericGoalCompletionValidator} in isolation, independent of the full
 * {@code NativeToolLoopService} scripted scenario in {@code
 * NativeToolLoopServiceRobloxContinuationTest} - these pin down the exact trigger conditions.
 */
class GenericGoalCompletionValidatorTest {

    private final GenericGoalCompletionValidator validator = new GenericGoalCompletionValidator();

    @Test
    void noToolCallsAtAllIsNeverGated() {
        WorkflowCompletionContext context = context(0, true, "to nie jest lista folderow, potrzebuje kolejnego narzedzia");
        assertThat(validator.assess(context).complete()).isTrue();
    }

    @Test
    void nonBootstrapEvidencePresentIsNeverGatedRegardlessOfText() {
        WorkflowCompletionContext context = context(2, false, "to nie jest lista folderow, potrzebuje kolejnego narzedzia");
        assertThat(validator.assess(context).complete()).isTrue();
    }

    @Test
    void bootstrapOnlyEvidenceWithoutInsufficiencyTextIsAccepted() {
        // A bootstrap-only result CAN be a genuinely complete answer (e.g. "is Studio connected?").
        WorkflowCompletionContext context = context(1, true, "Yes, one Roblox Studio session named MyGame is currently connected.");
        assertThat(validator.assess(context).complete()).isTrue();
    }

    @Test
    void bootstrapOnlyEvidenceWithPolishInsufficiencyAdmissionIsBlocked() {
        WorkflowCompletionContext context = context(1, true,
                "Znalazlem otwarte Studio: MyGame. To nie jest jednak lista folderow, potrzebuje kolejnego narzedzia aby ja pobrac.");
        CompletionAssessment assessment = validator.assess(context);
        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("BOOTSTRAP_ONLY_EVIDENCE_INSUFFICIENT_ANSWER");
        assertThat(assessment.guidance()).contains("list the project's folders");
    }

    @Test
    void bootstrapOnlyEvidenceWithEnglishInsufficiencyAdmissionIsBlocked() {
        WorkflowCompletionContext context = context(1, true,
                "I found the open Studio session, but this doesn't actually contain the folder list - I need another tool.");
        CompletionAssessment assessment = validator.assess(context);
        assertThat(assessment.complete()).isFalse();
        assertThat(assessment.reason()).isEqualTo("BOOTSTRAP_ONLY_EVIDENCE_INSUFFICIENT_ANSWER");
    }

    @Test
    void guidanceEchoesTheOriginalUserRequest() {
        WorkflowCompletionContext context = new WorkflowCompletionContext(
                "request-1", "conversation-1", false, "", false, false, "",
                "list the project's folders", 1, true, "this is only found, i need another tool");
        CompletionAssessment assessment = validator.assess(context);
        assertThat(assessment.guidance()).contains("list the project's folders");
    }

    private WorkflowCompletionContext context(int toolCallCount, boolean bootstrapOnlyEvidence, String proposedFinalText) {
        return new WorkflowCompletionContext(
                "request-1", "conversation-1", false, "", false, false, "",
                "list the project's folders", toolCallCount, bootstrapOnlyEvidence, proposedFinalText);
    }
}
