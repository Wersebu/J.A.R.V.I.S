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

    @Test
    void detectsCasualUsedGpuPriceQuestionAsWebSearch() {
        assertThat(detector.detect("a po ile chodzi uzywany rtx 4070ti 16 gb"))
                .isEqualTo(ToolIntent.SEARCH_WEB);
    }

    @Test
    void detectsShortPriceFollowUpAsWebSearch() {
        assertThat(detector.detect("no ceny"))
                .isEqualTo(ToolIntent.SEARCH_WEB);
    }

    @Test
    void detectsCoordinateLookupAsLocation() {
        assertThat(detector.detect("znajdz wspolrzedne Nowa Wola 05-500"))
                .isEqualTo(ToolIntent.LOCATION);
    }

    @Test
    void detectsRouteCalculationAsLocation() {
        assertThat(detector.detect("oblicz trase Nowa Wola -> Garwolin"))
                .isEqualTo(ToolIntent.LOCATION);
    }

    @Test
    void detectsAddressGroupingRequestAsLocation() {
        assertThat(detector.detect("pogrupuj te adresy i zaproponuj optymalna kolejnosc"))
                .isEqualTo(ToolIntent.LOCATION);
    }
}
