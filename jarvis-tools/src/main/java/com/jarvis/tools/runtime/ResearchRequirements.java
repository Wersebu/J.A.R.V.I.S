package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marketplace research requirements inferred from a model-owned tool request.
 *
 * @param requestedCount requested number of concrete listings
 * @param requiredDomain required marketplace domain
 * @param concreteListingsRequired whether concrete listing URLs are required
 * @param priceRequired whether price evidence is required
 * @param condition requested condition
 * @param productType requested product type
 */
public record ResearchRequirements(
        int requestedCount,
        String requiredDomain,
        boolean concreteListingsRequired,
        boolean priceRequired,
        String condition,
        String productType
) {

    private static final Pattern COUNT_PATTERN = Pattern.compile("(?iu)\\b(?:top\\s*)?(\\d{1,2})\\b");

    /**
     * Builds requirements from user request and main-model tool request text.
     *
     * @param request request
     * @return requirements
     */
    public static ResearchRequirements from(ToolCallingRequest request) {
        MarketplaceDomainResolver resolver = new MarketplaceDomainResolver();
        String text = request.userMessage() + " " + request.goal() + " " + request.reason();
        String normalized = normalize(text);
        Optional<String> domain = resolver.resolve(text);
        boolean price = normalized.matches(".*\\b(cena|ceny|koszt|kosztuje|po ile|ile kosztuja|ile sa|ile chodza|ile chodzi|price|prices|market)\\b.*");
        boolean listingWords = normalized.matches(".*\\b(link|url|ofert|ogloszen|ogloszenie|listing|listings|kupic|buy)\\w*\\b.*");
        int count = explicitCount(normalized);
        if (count == 0 && normalized.matches(".*\\b(kilka|pare|parę|top|lista|tabela|oferty|listings)\\b.*")) {
            count = 5;
        }
        String condition = normalized.matches(".*\\b(uzywan|uzywane|uzywana|used|second\\s*hand|wtorn)\\w*\\b.*")
                ? "USED"
                : "UNKNOWN";
        String productType = normalized.matches(".*\\b(rtx|gtx|radeon|gpu|karta graficzna|grafik)\\b.*")
                ? "GPU"
                : "UNKNOWN";
        boolean productMarketPrice = price && !"UNKNOWN".equals(productType);
        boolean concrete = domain.isPresent() || listingWords || count > 0 || productMarketPrice;
        if (count == 0 && productMarketPrice) {
            count = 5;
        } else if (count == 0 && concrete && price) {
            count = 1;
        }
        return new ResearchRequirements(Math.min(Math.max(count, 0), 15), domain.orElse(""), concrete, price, condition, productType);
    }

    /**
     * Returns true when multiple concrete listings should be collected.
     *
     * @return true for multi-listing research
     */
    public boolean multiListing() {
        return concreteListingsRequired && requestedCount > 1;
    }

    private static int explicitCount(String normalized) {
        Matcher matcher = COUNT_PATTERN.matcher(normalized);
        while (matcher.find()) {
            int value = Integer.parseInt(matcher.group(1));
            if (value >= 2 && value <= 15) {
                return value;
            }
        }
        return 0;
    }

    private static String normalize(String value) {
        String noMarks = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
