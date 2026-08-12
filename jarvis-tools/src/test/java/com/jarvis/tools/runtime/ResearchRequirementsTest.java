package com.jarvis.tools.runtime;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.knowledge.KnowledgeMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchRequirementsTest {

    @Test
    void resolvesShortOlxAliasAndRequestedListingCount() {
        ResearchRequirements requirements = ResearchRequirements.from(request(
                "dasz 5 uzywanych ofert z olx dla RTX 3060 12GB?",
                "Find concrete used RTX 3060 offers",
                "Need marketplace listings"));

        assertThat(requirements.requiredDomain()).isEqualTo("olx.pl");
        assertThat(requirements.requestedCount()).isEqualTo(5);
        assertThat(requirements.concreteListingsRequired()).isTrue();
        assertThat(requirements.multiListing()).isTrue();
        assertThat(requirements.condition()).isEqualTo("USED");
        assertThat(requirements.productType()).isEqualTo("GPU");
    }

    @Test
    void defaultsSeveralOffersToFiveListings() {
        ResearchRequirements requirements = ResearchRequirements.from(request(
                "daj kilka ofert rtx 4060 ti z allegro",
                "Find listings",
                "Need concrete URLs"));

        assertThat(requirements.requiredDomain()).isEqualTo("allegro.pl");
        assertThat(requirements.requestedCount()).isEqualTo(5);
        assertThat(requirements.multiListing()).isTrue();
    }

    private ToolCallingRequest request(String message, String goal, String reason) {
        return new ToolCallingRequest(
                "request-test",
                "conversation-test",
                message,
                goal,
                reason,
                "Base prompt",
                new Brain(BrainType.FAST, "test", "gpt-oss:20b", "test", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        );
    }
}
