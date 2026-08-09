package com.jarvis.tools.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests lightweight advisory tool intent detection.
 */
class DefaultToolIntentDetectorTest {

    private final DefaultToolIntentDetector detector = new DefaultToolIntentDetector();

    @Test
    void detectsCurrentGoldRateAsWebSearch() {
        assertThat(detector.detect("podaj mi prosze aktualny kurs zlota"))
                .isEqualTo(ToolIntent.SEARCH_WEB);
    }

    @Test
    void detectsUsedMarketPriceAsWebSearch() {
        assertThat(detector.detect("ile kosztuje RTX 3060 12GB na rynku wtornym?"))
                .isEqualTo(ToolIntent.SEARCH_WEB);
    }
}
