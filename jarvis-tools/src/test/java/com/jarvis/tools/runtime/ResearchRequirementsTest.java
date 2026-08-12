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

    @Test
    void genericUsedGpuPriceRequestDefaultsToFiveConcreteListings() {
        ResearchRequirements requirements = ResearchRequirements.from(request(
                "siemka po ile sa uzywane 3060 12GB?",
                "Retrieve current market price for used RTX 3060 12GB",
                "Need current marketplace price."));

        assertThat(requirements.priceRequired()).isTrue();
        assertThat(requirements.marketplaceResearch()).isTrue();
        assertThat(requirements.concreteListingsRequired()).isTrue();
        assertThat(requirements.requestedCount()).isEqualTo(5);
        assertThat(requirements.targetListingCount()).isEqualTo(5);
        assertThat(requirements.multiListing()).isTrue();
        assertThat(requirements.condition()).isEqualTo("USED");
    }

    @Test
    void usedModelWithoutRtxPrefixIsSecondHandMarketplaceNotUsageStatistics() {
        ResearchRequirements requirements = ResearchRequirements.from(request(
                "po ile sa uzywane 3060 12GB?",
                "Retrieve current market price",
                "Need price"));

        assertThat(requirements.marketplaceResearch()).isTrue();
        assertThat(requirements.priceRequired()).isTrue();
        assertThat(requirements.condition()).isEqualTo("USED");
        assertThat(requirements.productType()).isEqualTo("GPU");
        assertThat(requirements.requestedCount()).isEqualTo(5);
        assertThat(requirements.targetListingCount()).isEqualTo(5);
    }

    @Test
    void keepsAlternativeMarketplaceDomains() {
        ResearchRequirements requirements = ResearchRequirements.from(request(
                "chodzilo mi o uzywane chce kupic taka olx albo allegro",
                "Find used marketplace listings",
                "Need concrete offers"));

        assertThat(requirements.marketplaceResearch()).isTrue();
        assertThat(requirements.allowedDomains()).containsExactlyInAnyOrder("olx.pl", "allegro.pl");
        assertThat(requirements.requestedCount()).isEqualTo(5);
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
