package com.jarvis.tools.runtime;

import com.jarvis.tools.ToolResult;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Evaluates whether web search results are relevant enough to ground an answer.
 */
public class WebSearchQualityEvaluator {

    private static final double ACCEPTANCE_THRESHOLD = 0.34d;
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "or", "the", "to", "for", "of", "in", "on", "with", "from",
            "i", "me", "my", "please", "link", "listing", "used", "current",
            "daj", "do", "dla", "jakis", "jakies", "konkretnej", "konkretny", "moze",
            "prosze", "sprawdz", "szukaj", "uzywany", "uzywane", "z", "ze"
    );

    private final MarketObservationExtractor marketObservationExtractor = new MarketObservationExtractor();
    private final WebEntityMatcher entityMatcher = new WebEntityMatcher();

    /**
     * Evaluates a WebSearchTool result.
     *
     * @param request user/tool request
     * @param result tool result
     * @return quality report
     */
    public WebSearchQualityReport evaluate(ToolCallingRequest request, ToolResult result) {
        if (result == null || !result.success() || !"web".equalsIgnoreCase(result.tool())) {
            return new WebSearchQualityReport(false, false, 0.0d, "No successful web search result.", List.of(), List.of(), null);
        }
        Object rawResults = result.data().get("results");
        if (!(rawResults instanceof List<?> list) || list.isEmpty()) {
            return new WebSearchQualityReport(false, false, 0.0d, "SearXNG returned no results.", List.of(), List.of(), null);
        }

        String query = text(result.data().get("query"));
        String intentText = request.userMessage() + " " + request.goal() + " " + request.reason() + " " + query;
        Set<String> terms = importantTerms(intentText);
        Set<String> desiredDomains = desiredDomains(intentText);
        EntityDescriptor requestedEntity = entityMatcher.describe(intentText);
        boolean requiresSpecificValue = requiresSpecificValue(intentText);
        boolean requiresMarketValue = marketObservationExtractor.requiresMarketValue(request);

        double bestScore = 0.0d;
        List<Map<String, Object>> accepted = new ArrayList<>();
        List<MarketObservation> observations = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String url = text(map.get("url"));
            String domain = domain(url);
            if (domain.isBlank()) {
                continue;
            }
            String rawCandidateText = text(map.get("title")) + " " + text(map.get("snippet")) + " "
                    + text(map.get("source")) + " " + domain;
            EntityMatchResult entityMatch = entityMatcher.match(requestedEntity, rawCandidateText);
            if (!entityMatch.accepted()) {
                bestScore = Math.max(bestScore, entityMatch.score());
                continue;
            }
            String haystack = normalize(rawCandidateText);
            double score = Math.min(1.0d, score(terms, desiredDomains, domain, haystack) + entityMatch.score() * 0.25d);
            bestScore = Math.max(bestScore, score);
            boolean valueFoundForResult = containsSpecificValue(haystack);
            List<MarketObservation> resultObservations = marketObservationExtractor.extract(request,
                    text(map.get("title")), text(map.get("snippet")), text(map.get("source")), url);
            if (score >= ACCEPTANCE_THRESHOLD && (!requiresSpecificValue || valueFoundForResult || !resultObservations.isEmpty())) {
                observations.addAll(resultObservations);
                accepted.add(Map.of(
                        "title", text(map.get("title")),
                        "url", url,
                        "snippet", text(map.get("snippet")),
                        "source", text(map.get("source")),
                        "domain", domain,
                        "relevanceScore", score,
                        "entityScore", entityMatch.score(),
                        "entityReason", entityMatch.reason(),
                        "valueFound", valueFoundForResult,
                        "marketObservations", resultObservations.size()
                ));
            }
        }

        MarketAnalysis marketAnalysis = MarketAnalysis.from(observations);
        boolean liveEvidenceSatisfied = !accepted.isEmpty()
                && (!requiresSpecificValue || accepted.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("valueFound")))
                || !observations.isEmpty());
        boolean marketSatisfied = !requiresMarketValue || !observations.isEmpty();
        boolean acceptedEnough = liveEvidenceSatisfied && marketSatisfied;
        String reason;
        if (acceptedEnough) {
            reason = requiresMarketValue
                    ? "Relevant web results and market value observations found."
                    : "Relevant web results found.";
        } else if (!accepted.isEmpty() && requiresSpecificValue) {
            reason = "Relevant result links found, but snippets did not contain enough requested numeric values. Read result pages or search again.";
        } else if (requiresMarketValue && marketAnalysis.count() > 0) {
            reason = "Relevant market evidence exists with low sample size.";
        } else {
            reason = "Search results did not match the requested entities/domains well enough.";
        }
        return new WebSearchQualityReport(acceptedEnough, liveEvidenceSatisfied, bestScore, reason, accepted, observations, marketAnalysis);
    }

    private double score(Set<String> terms, Set<String> desiredDomains, String domain, String haystack) {
        double score = 0.0d;
        int matchedTerms = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                matchedTerms++;
            }
        }
        if (!terms.isEmpty()) {
            score += Math.min(0.55d, (double) matchedTerms / terms.size());
        }
        if (desiredDomains.isEmpty() && matchedTerms >= 2) {
            score += 0.30d;
        }
        if (desiredDomains.isEmpty()) {
            score += 0.12d;
        } else if (desiredDomains.stream().anyMatch(domain::contains)) {
            score += 0.50d;
        } else {
            score -= 0.35d;
        }
        return Math.max(0.0d, Math.min(1.0d, score));
    }

    private Set<String> importantTerms(String text) {
        String normalized = normalize(text);
        Set<String> terms = new LinkedHashSet<>();
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) {
                continue;
            }
            terms.add(token);
        }
        return terms;
    }

    private Set<String> desiredDomains(String text) {
        String normalized = normalize(text);
        Set<String> domains = new LinkedHashSet<>();
        String previous = "";
        for (String token : normalized.split("[^a-z0-9.]+")) {
            String hostCandidate = token.replaceAll("^\\.+|\\.+$", "").replaceFirst("^www\\.", "");
            if (looksLikeDomain(hostCandidate)) {
                domains.add(hostCandidate);
            } else if (isDomainMarker(previous) && looksLikeSiteName(token)) {
                domains.add(token);
            }
            previous = token;
        }
        return domains;
    }

    private boolean isDomainMarker(String token) {
        return Set.of("z", "ze", "na", "from", "site", "strony", "stronie", "portalu", "serwisu").contains(token);
    }

    private boolean looksLikeDomain(String token) {
        return token.matches("[a-z0-9-]+(\\.[a-z0-9-]+)+")
                && token.substring(token.lastIndexOf('.') + 1).length() >= 2;
    }

    private boolean looksLikeSiteName(String token) {
        return token.length() >= 4
                && !STOP_WORDS.contains(token)
                && !token.matches(".*\\d.*")
                && !Set.of("price", "cena", "kurs", "market", "listing", "source", "strona").contains(token);
    }

    private boolean requiresSpecificValue(String text) {
        String normalized = normalize(text);
        return normalized.matches(".*\\b(cena|ceny|koszt|kosztuje|kurs|notowania|price|prices|rate)\\b.*");
    }

    private boolean containsSpecificValue(String text) {
        return text.matches(".*\\b\\d+[\\d., ]*\\s*(zl|z\\u0142|pln|usd|eur|gbp|\\$|\\u20ac)(?=$|\\s|[.,;:)]|<).*")
                || text.matches(".*\\b(\\$|\\u20ac)\\s*\\d+[\\d., ]*.*");
    }

    private String domain(String url) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return "";
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return normalized.startsWith("www.") ? normalized.substring(4) : normalized;
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    private String normalize(String value) {
        String noMarks = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT);
    }

    private String text(Object value) {
        return Objects.toString(value, "").trim();
    }
}
