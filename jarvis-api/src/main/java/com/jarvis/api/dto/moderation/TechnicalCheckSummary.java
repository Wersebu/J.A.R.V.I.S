package com.jarvis.api.dto.moderation;

import java.util.List;

/**
 * TopkiMC technical pre-check summary supplied as safe metadata.
 */
public record TechnicalCheckSummary(
        int length,
        int tagCount,
        int maxDepth,
        List<String> heuristicRiskSignals
) {

    public TechnicalCheckSummary {
        heuristicRiskSignals = heuristicRiskSignals == null ? List.of() : List.copyOf(heuristicRiskSignals);
    }
}
