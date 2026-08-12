package com.jarvis.tools.runtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Concrete marketplace listing collected during web research.
 *
 * @param title listing title
 * @param price price
 * @param currency price currency
 * @param condition item condition
 * @param source source domain
 * @param url concrete listing URL
 * @param httpStatus HTTP status used for verification
 * @param verified whether this listing was verified by a successful page read
 * @param verifiedAt verification timestamp
 * @param status verification status
 * @param confidence confidence score
 */
public record MarketplaceListing(
        String title,
        BigDecimal price,
        String currency,
        String condition,
        String source,
        String url,
        int httpStatus,
        boolean verified,
        Instant verifiedAt,
        ListingVerificationStatus status,
        double confidence
) {

    /**
     * Compatibility constructor for callers that already have a verified listing.
     *
     * @param title title
     * @param price price
     * @param currency currency
     * @param condition condition
     * @param source source
     * @param url URL
     * @param confidence confidence
     */
    public MarketplaceListing(
            String title,
            BigDecimal price,
            String currency,
            String condition,
            String source,
            String url,
            double confidence
    ) {
        this(title, price, currency, condition, source, url, 200, true, Instant.now(),
                ListingVerificationStatus.VERIFIED, confidence);
    }

    /**
     * Converts the listing to JSON-safe map form.
     *
     * @return map
     */
    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", title);
        values.put("price", price);
        values.put("currency", currency);
        values.put("condition", condition);
        values.put("source", source);
        values.put("url", url);
        values.put("domain", source);
        values.put("httpStatus", httpStatus);
        values.put("verified", verified);
        values.put("verifiedAt", verifiedAt == null ? "" : verifiedAt.toString());
        values.put("status", status == null ? "" : status.name());
        values.put("confidence", confidence);
        return values;
    }
}
