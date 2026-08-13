package com.jarvis.tools.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the exact marketplace verification scenario: three URL variants of the same
 * auction must collapse into one listing, and listings for a different GPU model/VRAM must be
 * rejected by semantic verification even though they are legitimate, live, read pages — a search
 * snippet or a successfully-read page is never sufficient by itself.
 *
 * <p>A fake {@link AIProvider} stands in for a real LLM, deciding ACCEPT/REJECT the same way a
 * real verifier would: by comparing the listing's stated model/VRAM against the search target,
 * not by keyword overlap.
 */
class MarketplaceVerificationRegressionTest {

    private static final String TARGET = "RTX 4060 Ti 16GB";

    @Test
    void exactTaskScenarioDeduplicatesAcrossHostVariantsAndRejectsWrongVariants() {
        AiListingVerifier aiVerifier = new AiListingVerifier(new ObjectMapper());
        FakeSemanticProvider provider = new FakeSemanticProvider();
        Brain brain = new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW);
        ListingVerifier verifier = (title, content) -> aiVerifier.verify(provider, brain, TARGET, title, content);
        MarketplaceListingExtractor extractor = new MarketplaceListingExtractor(verifier);

        ResearchRequirements requirements = new ResearchRequirements(5, "", new MarketplaceDomainConstraint(Set.of()),
                true, true, true, "UNKNOWN", "GPU", TARGET);
        MarketplaceListingCollector collector = new MarketplaceListingCollector(requirements, extractor);
        ToolCallingRequest request = request();

        // A: desktop URL, listingId=123
        collector.observe(request, readPage(
                "https://www.olx.pl/d/oferta/rtx-4060-ti-16gb-ID123.html",
                "RTX 4060 Ti 16GB", "Karta graficzna RTX 4060 Ti 16GB, cena 1600 zl"));
        assertThat(collector.listingsAsMaps()).hasSize(1);

        // B: mobile URL, same listingId=123 -> duplicate of A, must not add a second listing
        collector.observe(request, readPage(
                "https://m.olx.pl/d/oferta/rtx-4060-ti-16gb-ID123.html",
                "RTX 4060 Ti 16GB", "Karta graficzna RTX 4060 Ti 16GB, cena 1600 zl"));
        assertThat(collector.listingsAsMaps()).hasSize(1);

        // C: wrong GPU model + VRAM -> REJECT
        collector.observe(request, readPage(
                "https://www.olx.pl/d/oferta/rtx-3060-ti-8gb-ID456.html",
                "RTX 3060 Ti 8GB", "Karta graficzna RTX 3060 Ti 8GB, cena 990 zl"));
        assertThat(collector.listingsAsMaps()).hasSize(1);

        // D: completely different GPU generation -> REJECT
        collector.observe(request, readPage(
                "https://www.olx.pl/d/oferta/gtx-1080-8gb-ID789.html",
                "GTX 1080 8GB", "Karta graficzna GTX 1080 8GB, cena 500 zl"));
        assertThat(collector.listingsAsMaps()).hasSize(1);

        // E: different brand, same model+VRAM -> ACCEPT
        collector.observe(request, readPage(
                "https://www.olx.pl/d/oferta/gigabyte-rtx-4060-ti-gaming-oc-16g-ID999.html",
                "Gigabyte GeForce RTX 4060 Ti Gaming OC 16G",
                "Karta graficzna Gigabyte RTX 4060 Ti Gaming OC 16GB, cena 1650 zl"));

        assertThat(collector.listingsAsMaps()).hasSize(2);
        assertThat(collector.listingsAsMaps()).extracting(listing -> listing.get("url")).containsExactlyInAnyOrder(
                "https://www.olx.pl/d/oferta/rtx-4060-ti-16gb-ID123.html",
                "https://www.olx.pl/d/oferta/gigabyte-rtx-4060-ti-gaming-oc-16g-ID999.html"
        );

        assertThat(collector.metadata().get("validListingCount")).isEqualTo(2);
        assertThat(collector.metadata().get("requestedListingCount")).isEqualTo(5);
        assertThat(collector.satisfied()).isFalse();
        assertThat(collector.needsMore()).isTrue();
    }

    private ToolCallingRequest request() {
        return new ToolCallingRequest(
                "request-marketplace", "conversation-marketplace",
                "Sprawdz aktualna cene RTX 4060 Ti 16GB", "Find current price for RTX 4060 Ti 16GB", "verified pricing needed",
                "Base prompt", new Brain(BrainType.FAST, "stub", "stub-model", "stub", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        );
    }

    private ToolResult readPage(String url, String title, String content) {
        return new ToolResult(true, "web", "READ_WEB_PAGE", "request-marketplace", "conversation-marketplace", false,
                List.of("web:page"), "Web page read finished", Map.of(
                "url", url, "statusCode", 200, "title", title, "content", content, "links", List.of()
        ), "", "", false, "");
    }

    /**
     * Stands in for a real semantic verifier: rejects when the listing text names a different GPU
     * model/VRAM than the target, accepts otherwise — mirroring what an LLM verifier would decide,
     * without depending on a live model in this test.
     */
    private static final class FakeSemanticProvider implements AIProvider {

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return chat(brain, prompt, AIJobType.BACKGROUND);
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt, AIJobType jobType) {
            String normalized = prompt.toLowerCase(Locale.ROOT);
            if (normalized.contains("rtx 3060 ti") || normalized.contains("gtx 1080")) {
                return new ChatResponse("{\"decision\":\"REJECT\",\"confidence\":0.95,"
                        + "\"reason\":\"different GPU model and VRAM\",\"matchedProduct\":\"\",\"matchedVariant\":\"\",\"evidence\":[]}");
            }
            return new ChatResponse("{\"decision\":\"ACCEPT\",\"confidence\":0.9,"
                    + "\"reason\":\"matches target model and VRAM\",\"matchedProduct\":\"RTX 4060 Ti\",\"matchedVariant\":\"16GB\",\"evidence\":[]}");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }
    }
}
