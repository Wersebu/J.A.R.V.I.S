package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Marketplace research requirements inferred from a model-owned tool request.
 *
 * @param requestedCount requested number of concrete listings
 * @param requiredDomain first required marketplace domain for compatibility
 * @param domainConstraint allowed marketplace domains
 * @param marketplaceResearch whether verified marketplace research is required
 * @param concreteListingsRequired whether concrete listing URLs are required
 * @param priceRequired whether price evidence is required
 * @param condition requested condition
 * @param productType requested product type
 */
public record ResearchRequirements(
        int requestedCount,
        String requiredDomain,
        MarketplaceDomainConstraint domainConstraint,
        boolean marketplaceResearch,
        boolean concreteListingsRequired,
        boolean priceRequired,
        String condition,
        String productType
) {

    private static final Pattern COUNT_PATTERN = Pattern.compile("(?iu)\\b(?:top\\s*)?(\\d{1,2})\\b");
    private static final Pattern GPU_PATTERN = Pattern.compile(
            "(?iu)(?:\\b(?:rtx|gtx|radeon|rx)\\s*\\d{3,5}\\s*(?:ti|super|xt)?\\b|\\b\\d{3,5}\\s*(?:ti|super|xt)?\\s*(?:\\d{1,2}\\s*(?:gb|gib))?\\b)"
    );

    /**
     * Creates requirements with defensive defaults.
     */
    public ResearchRequirements {
        requiredDomain = requiredDomain == null ? "" : requiredDomain;
        domainConstraint = domainConstraint == null ? new MarketplaceDomainConstraint(Set.of()) : domainConstraint;
        condition = condition == null ? "UNKNOWN" : condition;
        productType = productType == null ? "UNKNOWN" : productType;
        requestedCount = Math.min(Math.max(requestedCount, 0), 15);
    }

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
        MarketplaceDomainConstraint domainConstraint = resolver.resolveConstraint(text);
        boolean price = normalized.matches(".*\\b(cena|ceny|koszt|kosztuje|po ile|ile kosztuja|ile sa|ile chodza|ile chodzi|price|prices|market)\\b.*");
        boolean listingWords = normalized.matches(".*\\b(link|url|ofert|ogloszen|ogloszenie|listing|listings|kupic|buy)\\w*\\b.*");
        boolean purchase = normalized.matches(".*\\b(chce kupic|kupic|szukam|buy|purchase)\\b.*");
        int count = explicitCount(normalized);
        if (count == 0 && normalized.matches(".*\\b(kilka|pare|top|lista|tabela|oferty|listings)\\b.*")) {
            count = 5;
        }
        String condition = normalized.matches(".*\\b(uzywan|uzywane|uzywana|uzywki|used|second\\s*hand|wtorn)\\w*\\b.*")
                ? "USED"
                : "UNKNOWN";
        String productType = normalized.matches(".*\\b(rtx|gtx|radeon|gpu|karta graficzna|grafik)\\b.*")
                || GPU_PATTERN.matcher(normalized).find()
                ? "GPU"
                : "UNKNOWN";
        boolean usedMarketplacePrice = price && "USED".equals(condition);
        boolean marketplaceResearch = usedMarketplacePrice || purchase || listingWords || domainConstraint.restricted();
        boolean productMarketPrice = price && (!"UNKNOWN".equals(productType) || marketplaceResearch);
        boolean concrete = marketplaceResearch || count > 0 || productMarketPrice;
        if (count == 0 && marketplaceResearch) {
            count = 5;
        } else if (count == 0 && concrete && price) {
            count = 1;
        }
        return new ResearchRequirements(
                count,
                domainConstraint.primaryDomain(),
                domainConstraint,
                marketplaceResearch,
                concrete,
                price,
                condition,
                productType
        );
    }

    /**
     * Returns true when multiple concrete listings should be collected.
     *
     * @return true for multi-listing research
     */
    public boolean multiListing() {
        return concreteListingsRequired && targetListingCount() > 1;
    }

    /**
     * Returns the effective number of listings used by all read budgets.
     *
     * @return target listing count
     */
    public int targetListingCount() {
        if (!concreteListingsRequired) {
            return 0;
        }
        if (marketplaceResearch && requestedCount == 0) {
            return 5;
        }
        return Math.max(1, requestedCount);
    }

    /**
     * Returns allowed marketplace domains.
     *
     * @return allowed domains
     */
    public Set<String> allowedDomains() {
        return domainConstraint.allowedDomains();
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
