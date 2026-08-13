package com.jarvis.tools.runtime;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for canonical marketplace listing identity: the same real-world auction must
 * resolve to the same identity regardless of desktop/mobile host variant or tracking parameters.
 */
class MarketplaceListingIdentityTest {

    @Test
    void desktopAndMobileHostVariantsOfTheSameListingIdShareIdentity() {
        String desktop = MarketplaceListingIdentity.forUrl("https://www.olx.pl/d/oferta/karta-graficzna-rtx-4060-ti-16gb-CID123-ID123.html");
        String mobile = MarketplaceListingIdentity.forUrl("https://m.olx.pl/d/oferta/karta-graficzna-rtx-4060-ti-16gb-CID123-ID123.html");

        assertThat(desktop).isNotBlank();
        assertThat(desktop).isEqualTo(mobile);
    }

    @Test
    void trackingParametersDoNotChangeIdentity() {
        String plain = MarketplaceListingIdentity.forUrl("https://www.olx.pl/d/oferta/rtx-4060-ti-16gb-ID123.html");
        String tracked = MarketplaceListingIdentity.forUrl("https://www.olx.pl/d/oferta/rtx-4060-ti-16gb-ID123.html?utm_source=search&reason=promoted");

        assertThat(plain).isEqualTo(tracked);
    }

    @Test
    void differentListingIdsOnTheSameDomainAreDistinctIdentities() {
        String first = MarketplaceListingIdentity.forUrl("https://www.olx.pl/d/oferta/rtx-4060-ti-16gb-ID123.html");
        String second = MarketplaceListingIdentity.forUrl("https://www.olx.pl/d/oferta/rtx-3060-ti-8gb-ID456.html");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void sameIdOnDifferentDomainsAreDistinctIdentities() {
        String olx = MarketplaceListingIdentity.forUrl("https://www.olx.pl/d/oferta/rtx-4060-ti-ID123.html");
        String allegro = MarketplaceListingIdentity.forUrl("https://allegro.pl/oferta/rtx-4060-ti-ID123.html");

        assertThat(olx).isNotEqualTo(allegro);
    }

    @Test
    void fallsBackToTitlePriceSourceWhenUrlHasNoRecognizableId() {
        String identity = MarketplaceListingIdentity.forTitlePriceSource(
                "RTX 4060 Ti 16GB", new BigDecimal("1600"), "olx.pl");

        assertThat(identity).contains("rtx 4060 ti 16gb").contains("1600").contains("olx.pl");
    }
}
