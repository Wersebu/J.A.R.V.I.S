package com.jarvis.common.companion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class DelegationRequestTest {
    @Test void countsExplicitIndependentAgentsFromOriginalMessage() {
        assertEquals(3, DelegationRequest.requestedTasks("Run three independent background agents: alpha, beta, gamma."));
        assertEquals(3, DelegationRequest.requestedTasks("Uruchom trzech niezależnych agentów w tle."));
        assertEquals(3, DelegationRequest.requestedTasks("Uruchom 3 niezależne agenty w tle."));
        assertEquals(1, DelegationRequest.requestedTasks("Run one background agent to research alpha."));
        assertEquals(0, DelegationRequest.requestedTasks("Show my Google Tasks and Calendar events."));
        assertEquals(0, DelegationRequest.requestedTasks("Do not start independent background agents."));
        assertEquals(0, DelegationRequest.requestedTasks("Explain independent background agents."));
    }
}
