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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects concrete marketplace listings from search results and read pages.
 */
public class MarketplaceListingCollector {

    private final ResearchRequirements requirements;
    private final MarketplaceListingExtractor listingExtractor;
    private final Set<String> queuedOrRead = new LinkedHashSet<>();
    private final Set<String> listingIdentities = new LinkedHashSet<>();
    private final Queue<MarketplaceListingCandidate> queue = new ArrayDeque<>();
    private final List<MarketplaceListing> listings = new ArrayList<>();
    private final Map<ListingVerificationStatus, Integer> statuses = new LinkedHashMap<>();

    /**
     * Creates a collector for the request.
     *
     * @param requirements requirements
     * @param observationExtractor observation extractor
     */
    public MarketplaceListingCollector(ResearchRequirements requirements, MarketObservationExtractor observationExtractor) {
        this(requirements, new MarketplaceListingExtractor());
    }

    /**
     * Creates a collector for the request.
     *
     * @param requirements requirements
     * @param listingExtractor listing extractor
     */
    public MarketplaceListingCollector(ResearchRequirements requirements, MarketplaceListingExtractor listingExtractor) {
        this.requirements = requirements;
        this.listingExtractor = listingExtractor;
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
        if (!requirements.concreteListingsRequired()) {
            return true;
        }
        return listings.size() >= targetCount();
    }

    /**
     * Returns true when more concrete listing reads are required.
     *
     * @return true when more are needed
     */
    public boolean needsMore() {
        return requirements.concreteListingsRequired() && !satisfied();
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
     * Returns verified listings as market observations for aggregate analysis.
     *
     * @return verified observations
     */
    public List<MarketObservation> marketObservations() {
        return listings.stream()
                .map(listing -> new MarketObservation(
                        listing.title(),
                        "",
                        listing.title(),
                        listing.price(),
                        listing.currency(),
                        listing.condition(),
                        listing.source(),
                        listing.url(),
                        listing.verifiedAt(),
                        listing.confidence(),
                        false
                ))
                .toList();
    }

    /**
     * Returns market analysis based only on verified marketplace listings.
     *
     * @return market analysis
     */
    public MarketAnalysis marketAnalysis() {
        return MarketAnalysis.from(marketObservations());
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
                "queuedCandidates", queue.size(),
                "verifiedMarketplaceListings", listings.size(),
                "listingStatusCounts", Map.copyOf(statuses)
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
        ListingVerificationStatus status = verificationStatus(result);
        if (status == ListingVerificationStatus.VERIFIED) {
            Optional<MarketplaceListing> listing = extractListing(request, result);
            if (listing.isPresent()) {
                addListing(listing.get());
            } else {
                count(ListingVerificationStatus.REJECTED);
            }
        } else {
            count(status);
        }
        if (result.success()) {
            observeLinks(result.data().get("links"));
        }
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
        if (!acceptableDomain(url)) {
            return Optional.empty();
        }
        return listingExtractor.extract(request, result);
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

    private void addListing(MarketplaceListing listing) {
        if (!listing.verified() || listing.status() != ListingVerificationStatus.VERIFIED) {
            count(ListingVerificationStatus.REJECTED);
            return;
        }
        if (!listingIdentities.add(canonicalIdentity(listing.url()))) {
            return;
        }
        listings.add(listing);
        count(ListingVerificationStatus.VERIFIED);
    }

    private ListingVerificationStatus verificationStatus(ToolResult result) {
        int statusCode = statusCode(result);
        if (!result.success()) {
            if (statusCode == 404 || statusCode == 410) {
                return ListingVerificationStatus.DEAD;
            }
            return ListingVerificationStatus.BLOCKED;
        }
        if (statusCode == 404 || statusCode == 410) {
            return ListingVerificationStatus.DEAD;
        }
        if (statusCode == 403 || statusCode == 429 || statusCode == 408) {
            return ListingVerificationStatus.BLOCKED;
        }
        if (statusCode < 200 || statusCode >= 300) {
            return ListingVerificationStatus.REJECTED;
        }
        return ListingVerificationStatus.VERIFIED;
    }

    private int statusCode(ToolResult result) {
        Object value = result.data().get("statusCode");
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = Objects.toString(value, "");
        if (text.isBlank()) {
            text = result.errorMessage();
        }
        Matcher matcher = Pattern.compile("\\b(\\d{3})\\b").matcher(Objects.toString(text, ""));
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return result.success() ? 200 : 0;
    }

    private void count(ListingVerificationStatus status) {
        statuses.merge(status, 1, Integer::sum);
    }

    private int targetCount() {
        return Math.max(1, requirements.requestedCount());
    }

    private String canonicalIdentity(String url) {
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return host + path;
        } catch (java.net.URISyntaxException exception) {
            return Objects.toString(url, "").split("\\?")[0];
        }
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
