package com.jarvis.tools.runtime;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WebSearchQualityEvaluatorTest {

    private final WebSearchQualityEvaluator evaluator = new WebSearchQualityEvaluator();

    @Test
    void rejectsRelevantLinkWithoutPriceAndUnrelatedPrice() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 4060 Ti"),
                webResult("RTX 4060 Ti 16GB - Allegro", "https://allegro.pl/oferta/rtx-4060-ti", "Karta graficzna RTX 4060 Ti 16GB",
                        "Audi A8 D3", "https://olx.pl/d/oferta/audi-a8", "Cena 18000 PLN"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketObservations()).isEmpty();
    }

    @Test
    void searchSnippetPriceIsDiscoveryOnlyForMarketplaceRequests() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 4060 Ti"),
                webResult("RTX 4060 Ti 16GB - Allegro", "https://allegro.pl/oferta/rtx-4060-ti",
                        "Uzywana karta graficzna RTX 4060 Ti 16GB cena 1299 PLN"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketAnalysis().count()).isZero();
        assertThat(report.marketObservations()).isEmpty();
        assertThat(report.acceptedResults()).singleElement()
                .satisfies(result -> {
                    assertThat(result.get("searchPriceHint")).isEqualTo(true);
                    assertThat(result.get("marketEvidenceVerified")).isEqualTo(false);
                });
    }

    @Test
    void acceptsPolishZlotySymbolFromMarketplaceSnippet() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 5060 Ti 16GB"),
                webResult("Rtx 5060 Ti 16 Gb - Niska cena na Allegro", "https://allegro.pl/listing?string=rtx+5060+ti+16+gb",
                        "Karta graficzna MSI GeForce RTX 5060 Ti Ventus 2X OC Plus 16GB GDDR7 128bit 4499,00 zł"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketAnalysis().count()).isZero();
        assertThat(report.marketObservations()).isEmpty();
        assertThat(report.acceptedResults()).singleElement()
                .satisfies(result -> {
                    assertThat(result.get("searchPriceHint")).isEqualTo(true);
                    assertThat(result.get("marketEvidenceVerified")).isEqualTo(false);
                });
    }

    @Test
    void rejectsDifferentGpuGenerationEvenWithPrice() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 5060 Ti 16GB"),
                webResult("RTX 4060 Ti 16GB - Niska cena na Allegro", "https://allegro.pl/oferta/rtx-4060-ti-16gb",
                        "Karta graficzna RTX 4060 Ti 16GB cena 1999 PLN"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketObservations()).isEmpty();
    }

    @Test
    void rejectsWrongGpuMemoryVariant() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 4060 Ti 16GB"),
                webResult("Gigabyte RTX 4060 Ti Eagle 8GB - OLX", "https://olx.pl/d/oferta/rtx-4060-ti-8gb",
                        "Karta graficzna RTX 4060 Ti 8GB cena 1050 PLN"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketObservations()).isEmpty();
    }

    @Test
    void rejectsWholeComputerWhenUserAskedForGpuPrice() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 4060 Ti"),
                webResult("Komputer gamingowy RTX 4060 Ti - OLX", "https://olx.pl/d/oferta/komputer-rtx-4060-ti",
                        "Komputer gamingowy z procesorem Ryzen i RTX 4060 Ti cena 3500 PLN"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketObservations()).isEmpty();
    }

    @Test
    void acceptsMultipleMatchingMarketObservationsWithMediumConfidence() {
        WebSearchQualityReport report = evaluator.evaluate(priceRequest("RTX 4060 Ti 16GB"),
                webResult(
                        "RTX 4060 Ti 16GB - OLX", "https://olx.pl/oferta/rtx-4060-ti-16gb-1",
                        "Karta graficzna RTX 4060 Ti 16GB cena 1200 PLN",
                        "RTX 4060 Ti 16GB - Allegro", "https://allegro.pl/oferta/rtx-4060-ti-16gb-2",
                        "Karta graficzna RTX 4060 Ti 16GB cena 1300 PLN",
                        "RTX 4060 Ti 16GB - OLX", "https://olx.pl/oferta/rtx-4060-ti-16gb-3",
                        "Karta graficzna RTX 4060 Ti 16GB cena 1400 PLN"));

        assertThat(report.accepted()).isFalse();
        assertThat(report.liveEvidenceSatisfied()).isFalse();
        assertThat(report.marketAnalysis().count()).isZero();
        assertThat(report.marketObservations()).isEmpty();
        assertThat(report.acceptedResults()).hasSize(3);
        assertThat(report.acceptedResults()).allSatisfy(result -> {
            assertThat(result).containsEntry("searchPriceHint", true);
            assertThat(result).containsEntry("marketEvidenceVerified", false);
        });
    }

    @Test
    void excludesMarketOutliersFromAggregateRange() {
        MarketAnalysis analysis = MarketAnalysis.from(List.of(
                observation("OLX", "1000"),
                observation("Allegro", "1100"),
                observation("OLX", "1200"),
                observation("Random", "5000")
        ));

        assertThat(analysis.count()).isEqualTo(3);
        assertThat(analysis.minimum()).isEqualByComparingTo("1000");
        assertThat(analysis.maximum()).isEqualByComparingTo("1200");
        assertThat(analysis.median()).isEqualByComparingTo("1100");
        assertThat(analysis.observations()).anyMatch(MarketObservation::outlier);
    }

    private ToolCallingRequest priceRequest(String entity) {
        return new ToolCallingRequest(
                "request-test",
                "conversation-test",
                "po ile jest " + entity + "?",
                "Retrieve current market price for " + entity,
                "Need current marketplace price.",
                "Base prompt",
                new Brain(BrainType.FAST, "test", "gpt-oss:20b", "test", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        );
    }

    private ToolResult webResult(String title, String url, String snippet) {
        return webResult(new Object[]{title, url, snippet});
    }

    private ToolResult webResult(Object... triples) {
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (int index = 0; index < triples.length; index += 3) {
            results.add(Map.of(
                    "title", triples[index],
                    "url", triples[index + 1],
                    "snippet", triples[index + 2],
                    "source", "Test"
            ));
        }
        return new ToolResult(true, "web", "SEARCH_WEB", "request-test", "conversation-test", false,
                List.of("web:search"), "Search finished", Map.of(
                "query", "RTX 4060 Ti cena",
                "results", results
        ), "", "", false, "");
    }

    private MarketObservation observation(String source, String amount) {
        return new MarketObservation(
                "RTX 4060 Ti",
                "16GB",
                "RTX 4060 Ti",
                new BigDecimal(amount),
                "PLN",
                "used",
                source,
                "https://example.com/" + source,
                Instant.parse("2026-08-11T00:00:00Z"),
                0.8d,
                false
        );
    }
}
