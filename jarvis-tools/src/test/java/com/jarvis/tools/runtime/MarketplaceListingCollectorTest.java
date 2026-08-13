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

    private final MarketObservationExtractor observationExtractor = new MarketObservationExtractor();
    private final MarketplaceListingExtractor extractor = new MarketplaceListingExtractor(alwaysAccept());

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
        MarketplaceListingExtractor productAwareExtractor = new MarketplaceListingExtractor(
                (title, content) -> (title + " " + content).toLowerCase(java.util.Locale.ROOT).contains("rtx 3060")
                        ? new ListingVerificationResult(true, 0.9d, "matches target", "RTX 3060", "12GB", List.of())
                        : ListingVerificationResult.reject("does not match target product"));
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), productAwareExtractor);

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

    @Test
    void marketPriceQuestionDefaultsToFiveConcreteListings() {
        ToolCallingRequest request = request("po ile sa uzywane RTX 3060 12GB?");
        ResearchRequirements requirements = ResearchRequirements.from(request);
        MarketplaceListingCollector collector = new MarketplaceListingCollector(requirements, extractor);

        assertThat(requirements.requestedCount()).isEqualTo(5);
        assertThat(collector.satisfied()).isFalse();
    }

    @Test
    void rejectsDeadListingPages() {
        ToolCallingRequest request = request("daj oferte RTX 3060 12GB z olx");
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), extractor);

        collector.observe(request, failedReadPage(
                "https://www.olx.pl/d/oferta/dead-rtx-3060-12gb-CID99-ID404.html",
                "Web page returned HTTP 404"
        ));

        assertThat(collector.listingsAsMaps()).isEmpty();
        assertThat(collector.metadata().get("listingStatusCounts").toString()).contains("DEAD=1");
    }

    @Test
    void searchPriceHintIsIgnoredWhenReadPageHasDifferentPrice() {
        ToolCallingRequest request = request("po ile sa uzywane RTX 3060 12GB z olx?");
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), extractor);
        String url = "https://www.olx.pl/d/oferta/palit-rtx-3060-12gb-CID99-ID1.html";

        collector.observe(request, searchResult("Palit RTX 3060 12GB 920 zl", url, "Cena 920 zl"));
        collector.observe(request, readPage(
                url,
                "Palit RTX 3060 12GB",
                "Aktualna cena 800 PLN. Uzywana karta graficzna RTX 3060 12GB.",
                List.of()));

        assertThat(collector.listingsAsMaps()).singleElement()
                .satisfies(listing -> assertThat(listing.get("price").toString()).isEqualTo("800"));
    }

    @Test
    void storesExactVerifiedListingUrlAndAtomicFields() {
        ToolCallingRequest request = request("ile kosztuje Gigabyte RTX 3060 12GB?");
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), extractor);
        String exactUrl = "https://www.olx.pl/d/oferta/gigabyte-rtx-3060-12gb-CID99-ID1.html?search_reason=search%7Cpromoted";

        collector.observe(request, readPage(
                exactUrl,
                "Gigabyte RTX 3060 Gaming OC 12GB",
                "structured data offer price: 950 PLN. Uzywana karta graficzna Gigabyte RTX 3060 12GB.",
                List.of()));

        assertThat(collector.listingsAsMaps()).singleElement().satisfies(listing -> {
            assertThat(listing).containsEntry("title", "Gigabyte RTX 3060 Gaming OC 12GB");
            assertThat(listing).containsEntry("url", exactUrl);
            assertThat(listing).containsEntry("currency", "PLN");
            assertThat(listing).containsEntry("httpStatus", 200);
            assertThat(listing).containsEntry("verified", true);
            assertThat(listing).containsEntry("status", "VERIFIED");
            assertThat(listing.get("price").toString()).isEqualTo("950");
        });
    }

    @Test
    void parsesDollarPricesWithoutFailingOnMissingRegexGroups() {
        ToolCallingRequest request = request("po ile jest RTX 3060 12GB w USD?");
        List<MarketObservation> observations = observationExtractor.extract(
                request,
                "Legacy Nvidia RTX 3060 12GB returns",
                "Legacy Nvidia RTX 3060 12GB returns, priced at $339 with limited stock.",
                "Tom's Hardware",
                "https://www.tomshardware.com/news/rtx-3060-12gb-price");

        assertThat(observations).singleElement().satisfies(observation -> {
            assertThat(observation.currency()).isEqualTo("USD");
            assertThat(observation.price().toString()).isEqualTo("339");
        });
    }

    @Test
    void deduplicatesSameCanonicalListingWithoutLosingExactFirstUrl() {
        ToolCallingRequest request = request("daj 2 oferty RTX 3060 12GB z olx");
        MarketplaceListingCollector collector = new MarketplaceListingCollector(ResearchRequirements.from(request), extractor);
        String firstUrl = "https://www.olx.pl/d/oferta/gigabyte-rtx-3060-12gb-CID99-ID1.html?search_reason=search%7Cpromoted";
        String duplicateUrl = "https://www.olx.pl/d/oferta/gigabyte-rtx-3060-12gb-CID99-ID1.html?utm_source=test";

        collector.observe(request, readPage(
                firstUrl,
                "Gigabyte RTX 3060 Gaming OC 12GB",
                "Cena 950 PLN. Uzywana karta graficzna Gigabyte RTX 3060 12GB.",
                List.of()));
        collector.observe(request, readPage(
                duplicateUrl,
                "Gigabyte RTX 3060 Gaming OC 12GB",
                "Cena 950 PLN. Uzywana karta graficzna Gigabyte RTX 3060 12GB.",
                List.of()));

        assertThat(collector.listingsAsMaps()).singleElement()
                .satisfies(listing -> assertThat(listing.get("url")).isEqualTo(firstUrl));
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
                "statusCode", 200,
                "title", title,
                "content", content,
                "links", links
        ), "", "", false, "");
    }

    private static ListingVerifier alwaysAccept() {
        return (title, content) -> new ListingVerificationResult(true, 0.9d, "test-accept", "", "", List.of());
    }

    private ToolResult failedReadPage(String url, String errorMessage) {
        return new ToolResult(false, "web", "READ_WEB_PAGE", "request-test", "conversation-test", false,
                List.of("web:page"), errorMessage, Map.of(
                "url", url,
                "statusCode", 404
        ), errorMessage, "", false, "");
    }
}
