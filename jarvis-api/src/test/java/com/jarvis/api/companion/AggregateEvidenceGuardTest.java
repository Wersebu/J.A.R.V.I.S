package com.jarvis.api.companion;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AggregateEvidenceGuardTest {
    @Test void legacyPlainEvidenceRemainsSupportedAndCannotIntroduceANewVenue(){assertThat(AggregateEvidenceGuard.incomplete("Verified worker result")).isTrue();assertThatCode(()->AggregateEvidenceGuard.requireGroundedVenues("Verified worker result","Verified worker result","")).doesNotThrowAnyException();assertThatThrownBy(()->AggregateEvidenceGuard.requireGroundedVenues("CSS NASDAQ","Verified worker result","")).hasMessageContaining("venue");}
    private final String evidence="{\"outcomes\":[{\"name\":\"CSS NASDAQ\",\"state\":\"FAILED\",\"result\":\"\"},{\"state\":\"COMPLETED\",\"result\":\"USD 4.0754 PLN\"}]}";
    @Test void failedLookupAndWorkerNameCannotEstablishVenue(){assertThatThrownBy(()->AggregateEvidenceGuard.requireGroundedVenues("CSS (NASDAQ): brak danych",evidence,"Sprawdź CSS")).hasMessageContaining("venue");}
    @Test void explicitUserVenueAndHonestPartialRemainAllowed(){assertThatCode(()->AggregateEvidenceGuard.requireGroundedVenues("CSS (NASDAQ): brak danych",evidence,"Sprawdź CSS na NASDAQ")).doesNotThrowAnyException();assertThatCode(()->AggregateEvidenceGuard.requireGroundedVenues("USD 4.0754 PLN. Nie uzyskano danych CSS.",evidence,"Sprawdź USD i CSS")).doesNotThrowAnyException();}
}
