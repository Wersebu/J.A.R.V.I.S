package com.jarvis.tools.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Aggregated runtime market evidence.
 *
 * @param observations observations including outlier flags
 * @param count number of non-outlier observations
 * @param minimum minimum non-outlier price
 * @param maximum maximum non-outlier price
 * @param median median non-outlier price
 * @param mean mean non-outlier price
 * @param confidence LOW, MEDIUM or HIGH
 */
public record MarketAnalysis(
        List<MarketObservation> observations,
        int count,
        BigDecimal minimum,
        BigDecimal maximum,
        BigDecimal median,
        BigDecimal mean,
        String confidence
) {

    /**
     * Builds deterministic statistics for a set of observations.
     *
     * @param observations raw observations
     * @return market analysis
     */
    public static MarketAnalysis from(List<MarketObservation> observations) {
        List<MarketObservation> sorted = observations == null ? List.of() : observations.stream()
                .sorted(Comparator.comparing(MarketObservation::price))
                .toList();
        if (sorted.isEmpty()) {
            return new MarketAnalysis(List.of(), 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "LOW");
        }
        List<MarketObservation> flagged = flagOutliers(sorted);
        List<BigDecimal> values = flagged.stream()
                .filter(observation -> !observation.outlier())
                .map(MarketObservation::price)
                .sorted()
                .toList();
        if (values.isEmpty()) {
            values = flagged.stream().map(MarketObservation::price).sorted().toList();
        }
        BigDecimal minimum = values.getFirst();
        BigDecimal maximum = values.getLast();
        BigDecimal median = median(values);
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
        return new MarketAnalysis(flagged, values.size(), minimum, maximum, median, mean, confidence(values.size(), flagged));
    }

    /**
     * Returns the analysis as JSON-friendly maps.
     *
     * @return map representation
     */
    public java.util.Map<String, Object> toMap() {
        return java.util.Map.of(
                "count", count,
                "minimum", minimum,
                "maximum", maximum,
                "median", median,
                "mean", mean,
                "confidence", confidence,
                "observations", observations.stream().map(MarketAnalysis::observationMap).toList()
        );
    }

    private static List<MarketObservation> flagOutliers(List<MarketObservation> sorted) {
        if (sorted.size() < 4) {
            return sorted;
        }
        BigDecimal median = median(sorted.stream().map(MarketObservation::price).toList());
        BigDecimal upperSoftLimit = median.multiply(BigDecimal.valueOf(1.8d));
        BigDecimal lowerSoftLimit = median.multiply(BigDecimal.valueOf(0.45d));
        List<MarketObservation> flagged = new ArrayList<>();
        for (MarketObservation observation : sorted) {
            boolean outlier = observation.price().compareTo(upperSoftLimit) > 0
                    || observation.price().compareTo(lowerSoftLimit) < 0;
            flagged.add(observation.withOutlier(outlier));
        }
        return flagged;
    }

    private static BigDecimal median(List<BigDecimal> values) {
        int size = values.size();
        if (size % 2 == 1) {
            return values.get(size / 2);
        }
        return values.get(size / 2 - 1).add(values.get(size / 2)).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    private static String confidence(int count, List<MarketObservation> observations) {
        long domains = observations.stream()
                .filter(observation -> !observation.outlier())
                .map(MarketObservation::source)
                .distinct()
                .count();
        if (count >= 5 && domains >= 2) {
            return "HIGH";
        }
        if (count >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static java.util.Map<String, Object> observationMap(MarketObservation observation) {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("entity", observation.entity());
        values.put("variant", observation.variant());
        values.put("title", observation.title());
        values.put("price", observation.price());
        values.put("currency", observation.currency());
        values.put("condition", observation.condition());
        values.put("source", observation.source());
        values.put("url", observation.url());
        values.put("timestamp", observation.timestamp().toString());
        values.put("confidence", observation.confidence());
        values.put("outlier", observation.outlier());
        return values;
    }
}
