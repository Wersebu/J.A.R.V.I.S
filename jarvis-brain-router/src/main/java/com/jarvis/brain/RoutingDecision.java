package com.jarvis.brain;

import com.jarvis.common.ai.BrainType;

/**
 * Internal deterministic routing decision.
 *
 * @param brainType selected brain type
 * @param reason selection reason
 */
record RoutingDecision(BrainType brainType, String reason) {
}
