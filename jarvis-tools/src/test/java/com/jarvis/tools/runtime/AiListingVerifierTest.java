package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiListingVerifierTest {

    private final Brain brain = new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW);

    @Test
    void parsesAcceptDecisionWithStructuredFields() {
        AiListingVerifier verifier = new AiListingVerifier(new ObjectMapper());
        AIProvider provider = respondingWith("{\"decision\":\"ACCEPT\",\"confidence\":0.88,\"reason\":\"matches\","
                + "\"matchedProduct\":\"RTX 4060 Ti\",\"matchedVariant\":\"16GB\",\"evidence\":[\"title says 16GB\"]}");

        ListingVerificationResult result = verifier.verify(provider, brain, "RTX 4060 Ti 16GB", "RTX 4060 Ti 16GB", "content");

        assertThat(result.accepted()).isTrue();
        assertThat(result.confidence()).isEqualTo(0.88d);
        assertThat(result.matchedProduct()).isEqualTo("RTX 4060 Ti");
        assertThat(result.matchedVariant()).isEqualTo("16GB");
        assertThat(result.evidence()).containsExactly("title says 16GB");
    }

    @Test
    void parsesRejectDecision() {
        AiListingVerifier verifier = new AiListingVerifier(new ObjectMapper());
        AIProvider provider = respondingWith("{\"decision\":\"REJECT\",\"confidence\":0.95,"
                + "\"reason\":\"different VRAM\",\"matchedProduct\":\"\",\"matchedVariant\":\"\",\"evidence\":[]}");

        ListingVerificationResult result = verifier.verify(provider, brain, "RTX 4060 Ti 16GB", "RTX 4060 Ti 8GB", "content");

        assertThat(result.accepted()).isFalse();
        assertThat(result.reason()).isEqualTo("different VRAM");
    }

    @Test
    void rejectsWhenResponseIsUnparseable() {
        AiListingVerifier verifier = new AiListingVerifier(new ObjectMapper());
        AIProvider provider = respondingWith("not json at all");

        ListingVerificationResult result = verifier.verify(provider, brain, "RTX 4060 Ti 16GB", "title", "content");

        assertThat(result.accepted()).isFalse();
    }

    @Test
    void acceptsWithLowConfidenceWhenNoSearchTargetIsAvailable() {
        AiListingVerifier verifier = new AiListingVerifier(new ObjectMapper());
        AIProvider provider = respondingWith("irrelevant, should not be called meaningfully");

        ListingVerificationResult result = verifier.verify(provider, brain, "", "title", "content");

        assertThat(result.accepted()).isTrue();
        assertThat(result.confidence()).isLessThan(0.5d);
    }

    @Test
    void rejectsWhenProviderThrows() {
        AiListingVerifier verifier = new AiListingVerifier(new ObjectMapper());
        AIProvider provider = new AIProvider() {
            @Override
            public String provider() {
                return "failing";
            }

            @Override
            public ChatResponse chat(Brain brain, String prompt) {
                throw new RuntimeException("provider unavailable");
            }

            @Override
            public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
            }
        };

        ListingVerificationResult result = verifier.verify(provider, brain, "RTX 4060 Ti 16GB", "title", "content");

        assertThat(result.accepted()).isFalse();
    }

    private AIProvider respondingWith(String json) {
        return new AIProvider() {
            @Override
            public String provider() {
                return "fake";
            }

            @Override
            public ChatResponse chat(Brain brain, String prompt) {
                return new ChatResponse(json);
            }

            @Override
            public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
                return new ChatResponse(json);
            }

            @Override
            public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
            }
        };
    }
}
