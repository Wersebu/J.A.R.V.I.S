package com.jarvis.tools.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InformationFreshnessEvaluatorTest {

    private final InformationFreshnessEvaluator evaluator = new InformationFreshnessEvaluator();

    @Test
    void keepsStaticConceptualQuestionsOffline() {
        assertThat(evaluator.evaluate("co to jest VRAM?", "", ""))
                .isEqualTo(InformationFreshness.STATIC);
    }

    @Test
    void requiresLiveEvidenceForRatesAndCurrentPrices() {
        assertThat(evaluator.evaluate("jaki jest dzisiaj kurs USD do PLN?", "", ""))
                .isEqualTo(InformationFreshness.MUST_BE_LIVE);

        assertThat(evaluator.evaluate("po ile sa uzywane RTX 5060 Ti?", "", ""))
                .isEqualTo(InformationFreshness.MUST_BE_LIVE);
    }
}
