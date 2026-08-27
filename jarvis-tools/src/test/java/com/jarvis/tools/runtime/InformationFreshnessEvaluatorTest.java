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

    // Regression for the reported production bug: a plain-substring check for the English term
    // "now" matched inside the Polish place name "Nowej" ("Nowa Wola", as in a start-point address),
    // wrongly classifying a schedule-creation request as MUST_BE_LIVE and driving the native tool
    // loop into an unbounded "live evidence required" retry cycle even though nothing in the request
    // was actually about current/live information.
    @Test
    void placeNameContainingTheSubstringNowDoesNotFalselyTriggerLiveEvidence() {
        assertThat(evaluator.evaluate(
                "wolalbym robic audyty rownomiernie przez caly miesiac we wtorki i srody, "
                        + "startujemy z Nowej Woli 05-500", "", ""))
                .isEqualTo(InformationFreshness.STATIC);
    }

    @Test
    void wordBoundaryStillCatchesTheGenuineStandaloneLiveTerm() {
        assertThat(evaluator.evaluate("jaka jest cena tego teraz", "", ""))
                .isEqualTo(InformationFreshness.MUST_BE_LIVE);
    }
}
