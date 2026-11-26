package com.jarvis.common.run;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PublicWebRequestTest {
    @Test void recognizesNaturalCombinedQuestionWithoutExplicitAgentRequest() {
        assertTrue(PublicWebRequest.requires("ile kosztuje dolar?"));
        assertTrue(PublicWebRequest.exchangeRate("ile kosztuje dolar?"));
        assertEquals(3, PublicWebRequest.independentQuestions(
                "Jarvis, jaka jest pogoda w Warszawie, ile kosztuje dolar i kiedy wychodzi GTA VI?").size());
        assertTrue(PublicWebRequest.independentQuestions("Jaka jest pogoda, następnie na podstawie prognozy wybierz hotel?").isEmpty());
    }

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
