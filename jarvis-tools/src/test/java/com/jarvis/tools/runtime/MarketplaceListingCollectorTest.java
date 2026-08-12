package com.jarvis.tools.runtime;

import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MarketplaceListingCollectorTest {

    private final MarketObservationExtractor extractor = new MarketObservationExtractor();

    @Test
    void expandsLinksFromReadSearchPageIntoConcreteListingReads() {
        ToolCallingRequest request = request("daj 2 oferty z olx RTX 3060 12GB");
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), extractor);

        collector.observe(request, searchResult(
                "RTX 3060 - OLX",
                "https://www.olx.pl/elektronika/komputery/q-rtx-3060/",
                "Oferty RTX 3060"));

        ToolAction first = collector.nextReadAction().orElseThrow();
        assertThat(first.arguments().get("url")).isEqualTo("https://www.olx.pl/elektronika/komputery/q-rtx-3060/");

        collector.observe(request, readPage("https://www.olx.pl/elektronika/komputery/q-rtx-3060/", "Search", "", List.of(
                Map.of("title", "Acer Predator RTX 3060 12GB", "url", "https://www.olx.pl/d/oferta/acer-predator-rtx-3060-12gb-CID99-ID1.html"),
                Map.of("title", "Gigabyte RTX 3060 12GB", "url", "https://www.olx.pl/d/oferta/gigabyte-rtx-3060-12gb-CID99-ID2.html")
        )));

        assertThat(collector.nextReadAction().orElseThrow().arguments().get("url"))
                .isEqualTo("https://www.olx.pl/d/oferta/acer-predator-rtx-3060-12gb-CID99-ID1.html");
        assertThat(collector.nextReadAction().orElseThrow().arguments().get("url"))
                .isEqualTo("https://www.olx.pl/d/oferta/gigabyte-rtx-3060-12gb-CID99-ID2.html");
    }

    @Test
    void collectsOnlyConcreteListingsWithMatchingPrices() {
        ToolCallingRequest request = request("daj 2 uzywane oferty z olx RTX 3060 12GB");
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), extractor);

        collector.observe(request, readPage(
                "https://www.olx.pl/d/oferta/acer-predator-rtx-3060-12gb-CID99-ID1.html",
                "Acer Predator NVIDIA GeForce RTX 3060 12GB",
                "Uzywana karta graficzna RTX 3060 12GB cena 950 zl",
                List.of()));
        collector.observe(request, readPage(
                "https://www.olx.pl/d/oferta/audi-a8-CID5-ID3.html",
                "Audi A8",
                "Cena 18000 zl",
                List.of()));

        assertThat(collector.listingsAsMaps()).hasSize(1);
        assertThat(collector.listingsAsMaps().getFirst())
                .containsEntry("url", "https://www.olx.pl/d/oferta/acer-predator-rtx-3060-12gb-CID99-ID1.html")
                .containsEntry("currency", "PLN");
        assertThat(collector.satisfied()).isFalse();
    }

    private ToolCallingRequest request(String message) {
        return new ToolCallingRequest(
                "request-test",
                "conversation-test",
                message,
                "Retrieve concrete marketplace listings",
                "Need prices and URLs",
                "Base prompt",
                new Brain(BrainType.FAST, "test", "gpt-oss:20b", "test", "", 0L, ReasoningLevel.LOW),
                KnowledgeMode.FAST
        );
    }

    private ToolResult searchResult(String title, String url, String snippet) {
        return new ToolResult(true, "web", "SEARCH_WEB", "request-test", "conversation-test", false,
                List.of("web:search"), "Search finished", Map.of(
                "query", "rtx 3060 olx",
                "results", List.of(Map.of("title", title, "url", url, "snippet", snippet, "source", "OLX"))
        ), "", "", false, "");
    }

    private ToolResult readPage(String url, String title, String content, List<Map<String, String>> links) {
        return new ToolResult(true, "web", "READ_WEB_PAGE", "request-test", "conversation-test", false,
                List.of("web:page"), "Web page read finished", Map.of(
                "url", url,
                "title", title,
                "content", content,
                "links", links
        ), "", "", false, "");
    }
}
