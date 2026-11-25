package com.jarvis.common.run;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PublicWebRequestTest {
    @Test void routesActualPublicQuestionsAndSplitsOnlyIndependentQuestions() {
        String weather="Jaka jest aktualna pogoda w Warszawie?";
        String currency="Jaki jest aktualny kurs USD do PLN?";
        String release="Sprawdź w internecie, kiedy wychodzi GTA VI.";
        assertTrue(PublicWebRequest.requires(weather));assertTrue(PublicWebRequest.requires(currency));assertTrue(PublicWebRequest.requires(release));
        assertTrue(PublicWebRequest.exchangeRate(currency));
        assertEquals(3,PublicWebRequest.independentQuestions(weather+" "+currency+" "+release).size());
        assertTrue(PublicWebRequest.independentQuestions(weather+" Następnie na podstawie wyniku zaplanuj podróż?").isEmpty());
        assertFalse(PublicWebRequest.requires("Sprawdź aktualne pliki projektu w workspace"));
    }
}
