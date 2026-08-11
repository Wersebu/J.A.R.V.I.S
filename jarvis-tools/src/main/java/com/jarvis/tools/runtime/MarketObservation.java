package com.jarvis.tools.runtime;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Runtime-only observation of one market value found during web research.
 *
 * @param entity searched entity
 * @param variant optional variant
 * @param title source title
 * @param price parsed price
 * @param currency parsed currency
 * @param condition item condition when known
 * @param source source name or domain
 * @param url source URL
 * @param timestamp observation timestamp
 * @param confidence extraction confidence from 0.0 to 1.0
 * @param outlier whether the value looks like an outlier within the current set
 */
public record MarketObservation(
        String entity,
        String variant,
        String title,
        BigDecimal price,
        String currency,
        String condition,
        String source,
        String url,
        Instant timestamp,
        double confidence,
        boolean outlier
) {

    /**
     * Returns a copy with outlier status changed.
     *
     * @param value outlier status
     * @return updated observation
     */
    public MarketObservation withOutlier(boolean value) {
        return new MarketObservation(entity, variant, title, price, currency, condition, source, url, timestamp, confidence, value);
    }
}
