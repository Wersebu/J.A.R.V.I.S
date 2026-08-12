package com.jarvis.tools.runtime;

import java.math.BigDecimal;
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
 * @param confidence confidence score
 */
public record MarketplaceListing(
        String title,
        BigDecimal price,
        String currency,
        String condition,
        String source,
        String url,
        double confidence
) {

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
        values.put("confidence", confidence);
        return values;
    }
}
