package com.jarvis.tools.runtime;

import java.util.List;
import java.util.Map;

/**
 * Quality report for one WebSearchTool result set.
 *
 * @param accepted whether the result set is relevant enough to support a final answer
 * @param score best relevance score
 * @param reason short diagnostic reason
 * @param acceptedResults trusted relevant results
 */
public record WebSearchQualityReport(
        boolean accepted,
        double score,
        String reason,
        List<Map<String, Object>> acceptedResults
) {

    /**
     * Creates an immutable quality report.
     */
    public WebSearchQualityReport {
        reason = reason == null ? "" : reason;
        acceptedResults = acceptedResults == null ? List.of() : List.copyOf(acceptedResults);
    }
}
