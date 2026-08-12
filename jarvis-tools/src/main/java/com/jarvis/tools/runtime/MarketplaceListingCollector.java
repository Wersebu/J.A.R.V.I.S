package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;
import com.jarvis.tools.web.WebUrlClassifier;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

/**
 * Collects concrete marketplace listings from search results and read pages.
 */
public class MarketplaceListingCollector {

    private final ResearchRequirements requirements;
    private final MarketObservationExtractor observationExtractor;
    private final Set<String> queuedOrRead = new LinkedHashSet<>();
    private final Queue<MarketplaceListingCandidate> queue = new ArrayDeque<>();
    private final List<MarketplaceListing> listings = new ArrayList<>();

    /**
     * Creates a collector for the request.
     *
     * @param requirements requirements
     * @param observationExtractor observation extractor
     */
    public MarketplaceListingCollector(ResearchRequirements requirements, MarketObservationExtractor observationExtractor) {
        this.requirements = requirements;
        this.observationExtractor = observationExtractor;
    }

    /**
     * Observes a tool result and updates candidate/listing state.
     *
     * @param request request
     * @param result tool result
     */
    public void observe(ToolCallingRequest request, ToolResult result) {
        if (result == null || !"web".equalsIgnoreCase(result.tool())) {
            return;
        }
        if ("SEARCH_WEB".equalsIgnoreCase(result.operation())) {
            observeSearchResults(result.data().get("acceptedResults"), 120);
            observeSearchResults(result.data().get("results"), 80);
            return;
        }
        if ("READ_WEB_PAGE".equalsIgnoreCase(result.operation())) {
            observeReadPage(request, result);
        }
    }

    /**
     * Returns the next page-read action when more listings are needed.
     *
     * @return next action
     */
    public Optional<ToolAction> nextReadAction() {
        if (!needsMore()) {
            return Optional.empty();
        }
        while (!queue.isEmpty()) {
            MarketplaceListingCandidate candidate = queue.poll();
            if (!acceptableDomain(candidate.url()) || !queuedOrRead.add("read:" + candidate.url())) {
                continue;
            }
            return Optional.of(new ToolAction("TOOL_CALL", "web", "READ_WEB_PAGE",
                    Map.of("url", candidate.url()), "Read concrete marketplace listing candidate", ""));
        }
        return Optional.empty();
    }

    /**
     * Returns true when enough concrete listings were collected.
     *
     * @return true when satisfied
     */
    public boolean satisfied() {
        return !requirements.multiListing() || listings.size() >= requirements.requestedCount();
    }

    /**
     * Returns true when more concrete listing reads are required.
     *
     * @return true when more are needed
     */
    public boolean needsMore() {
        return requirements.multiListing() && !satisfied();
    }

    /**
     * Returns listings as compact maps.
     *
     * @return maps
     */
    public List<Map<String, Object>> listingsAsMaps() {
        return listings.stream().map(MarketplaceListing::toMap).toList();
    }

    /**
     * Returns collector metadata.
     *
     * @return metadata
     */
    public Map<String, Object> metadata() {
        return Map.of(
                "requestedListingCount", requirements.requestedCount(),
                "validListingCount", listings.size(),
                "researchSatisfied", satisfied(),
                "queuedCandidates", queue.size()
        );
    }

    private void observeSearchResults(Object rawResults, int priority) {
        if (!(rawResults instanceof List<?> list)) {
            return;
        }
        List<MarketplaceListingCandidate> candidates = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            addCandidate(candidates,
                    Objects.toString(map.get("title"), ""),
                    Objects.toString(map.get("url"), ""),
                    Objects.toString(map.get("source"), ""),
                    Objects.toString(map.get("snippet"), ""),
                    priority + candidateBoost(Objects.toString(map.get("url"), "")));
        }
        candidates.stream()
                .sorted(Comparator.comparingInt(MarketplaceListingCandidate::priority).reversed())
                .forEach(candidate -> queue.offer(candidate));
    }

    private void observeReadPage(ToolCallingRequest request, ToolResult result) {
        String url = Objects.toString(result.data().getOrDefault("url", ""), "");
        if (!url.isBlank()) {
            queuedOrRead.add("read:" + url);
        }
        if (result.success()) {
            extractListing(request, result).ifPresent(listings::add);
        }
        observeLinks(result.data().get("links"));
    }

    private void observeLinks(Object rawLinks) {
        if (!(rawLinks instanceof List<?> list)) {
            return;
        }
        List<MarketplaceListingCandidate> candidates = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            addCandidate(candidates,
                    Objects.toString(map.get("title"), ""),
                    Objects.toString(map.get("url"), ""),
                    WebUrlClassifier.domain(Objects.toString(map.get("url"), "")),
                    "",
                    160 + candidateBoost(Objects.toString(map.get("url"), "")));
        }
        candidates.stream()
                .sorted(Comparator.comparingInt(MarketplaceListingCandidate::priority).reversed())
                .forEach(candidate -> queue.offer(candidate));
    }

    private Optional<MarketplaceListing> extractListing(ToolCallingRequest request, ToolResult result) {
        String url = Objects.toString(result.data().getOrDefault("url", ""), "");
        if (!WebUrlClassifier.isConcreteListing(url) || !acceptableDomain(url)) {
            return Optional.empty();
        }
        String title = Objects.toString(result.data().getOrDefault("title", ""), "");
        String content = Objects.toString(result.data().getOrDefault("content", ""), "");
        List<MarketObservation> observations = observationExtractor.extract(request, title, content,
                WebUrlClassifier.domain(url), url);
        if (observations.isEmpty()) {
            return Optional.empty();
        }
        MarketObservation best = observations.getFirst();
        return Optional.of(new MarketplaceListing(
                title.isBlank() ? best.title() : title,
                best.price(),
                best.currency(),
                best.condition(),
                best.source(),
                best.url(),
                best.confidence()
        ));
    }

    private void addCandidate(List<MarketplaceListingCandidate> candidates, String title, String url, String source, String snippet, int priority) {
        if (url.isBlank() || !acceptableDomain(url)) {
            return;
        }
        if (!WebUrlClassifier.isConcreteListing(url) && !WebUrlClassifier.isSearchPage(url)) {
            return;
        }
        String key = "candidate:" + url;
        if (!queuedOrRead.add(key)) {
            return;
        }
        candidates.add(new MarketplaceListingCandidate(title, url, source, snippet, priority));
    }

    private int candidateBoost(String url) {
        if (WebUrlClassifier.isConcreteListing(url)) {
            return 100;
        }
        if (WebUrlClassifier.isSearchPage(url)) {
            return 20;
        }
        return 0;
    }

    private boolean acceptableDomain(String url) {
        if (requirements.requiredDomain().isBlank()) {
            return true;
        }
        String domain = WebUrlClassifier.domain(url).toLowerCase(Locale.ROOT);
        String required = requirements.requiredDomain().toLowerCase(Locale.ROOT);
        return domain.endsWith(required) || domain.contains(required);
    }
}
