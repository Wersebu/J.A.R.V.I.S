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

    /**
     * Evaluates a WebSearchTool result.
     *
     * @param request user/tool request
     * @param result tool result
     * @return quality report
     */
    public WebSearchQualityReport evaluate(ToolCallingRequest request, ToolResult result) {
        if (result == null || !result.success() || !"web".equalsIgnoreCase(result.tool())) {
            return new WebSearchQualityReport(false, 0.0d, "No successful web search result.", List.of());
        }
        Object rawResults = result.data().get("results");
        if (!(rawResults instanceof List<?> list) || list.isEmpty()) {
            return new WebSearchQualityReport(false, 0.0d, "SearXNG returned no results.", List.of());
        }

        String query = text(result.data().get("query"));
        String intentText = request.userMessage() + " " + request.goal() + " " + query;
        Set<String> terms = importantTerms(intentText);
        Set<String> desiredDomains = desiredDomains(intentText);
        boolean requiresSpecificValue = requiresSpecificValue(intentText);

        double bestScore = 0.0d;
        boolean valueFound = false;
        List<Map<String, Object>> accepted = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String url = text(map.get("url"));
            String domain = domain(url);
            if (domain.isBlank()) {
                continue;
            }
            String haystack = normalize(text(map.get("title")) + " " + text(map.get("snippet")) + " " + text(map.get("source")) + " " + domain);
            double score = score(terms, desiredDomains, domain, haystack);
            bestScore = Math.max(bestScore, score);
            valueFound = valueFound || containsSpecificValue(haystack);
            if (score >= ACCEPTANCE_THRESHOLD) {
                accepted.add(Map.of(
                        "title", text(map.get("title")),
                        "url", url,
                        "snippet", text(map.get("snippet")),
                        "source", text(map.get("source")),
                        "domain", domain,
                        "relevanceScore", score
                ));
            }
        }

        boolean acceptedEnough = !accepted.isEmpty() && (!requiresSpecificValue || valueFound);
        String reason;
        if (acceptedEnough) {
            reason = "Relevant web results found.";
        } else if (!accepted.isEmpty() && requiresSpecificValue) {
            reason = "Relevant result links found, but snippets did not contain the requested numeric value. Read result pages or search again.";
        } else {
            reason = "Search results did not match the requested entities/domains well enough.";
        }
        return new WebSearchQualityReport(acceptedEnough, bestScore, reason, accepted);
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
        return normalized.matches(".*\\b(cena|ceny|koszt|kosztuje|kurs|notowania|price|prices|rate|market)\\b.*");
    }

    private boolean containsSpecificValue(String text) {
        return text.matches(".*\\b\\d+[\\d., ]*\\s*(zl|pln|usd|eur|gbp|\\$|€)\\b.*")
                || text.matches(".*\\b(\\$|€)\\s*\\d+[\\d., ]*.*");
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
