package com.jarvis.tools.information;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.ToolRequest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InformationToolsTest {
 @Test void observedWeatherCodesHaveDeterministicDescriptionsAndUnknownCodesAreNotGuessed()throws Exception{
  var http=mock(InformationHttpClient.class);var json=new ObjectMapper();
  when(http.get(any())).thenReturn(json.readTree("{\"results\":[{\"latitude\":52,\"longitude\":21,\"timezone\":\"Europe/Warsaw\"}]}"),json.readTree("{\"daily\":{\"weather_code\":[51,3,999]},\"daily_units\":{}}"));
  var result=new WeatherTool(http).execute(new ToolRequest("weather","GET","c","r","","",Map.of("location","Warszawa","mode","daily","days",3)));
  assertThat(result.success()).isTrue();var descriptions=json.valueToTree(result.data().get("weatherCodeDescriptions"));assertThat(descriptions.path("51").asText()).isEqualTo("Lekka mżawka");assertThat(descriptions.path("3").asText()).isEqualTo("Całkowite zachmurzenie");assertThat(descriptions.path("999").asText()).contains("Nieznany");assertThat(descriptions.size()).isEqualTo(3);
 }
 @Test void weatherGeocodesAndReturnsCompactForecastWithSourceTimezoneAndUnits()throws Exception{
  var http=mock(InformationHttpClient.class);var json=new ObjectMapper();
  when(http.get(any())).thenReturn(json.readTree("{\"results\":[{\"name\":\"Warsaw\",\"country\":\"Poland\",\"latitude\":52.23,\"longitude\":21.01,\"timezone\":\"Europe/Warsaw\"}]}"),json.readTree("{\"timezone\":\"Europe/Warsaw\",\"daily\":{\"time\":[\"2026-11-29\"],\"temperature_2m_max\":[8],\"apparent_temperature_max\":[6],\"precipitation_sum\":[0.2],\"wind_speed_10m_max\":[12]},\"daily_units\":{\"temperature_2m_max\":\"C\"}}"));
  var result=new WeatherTool(http).execute(new ToolRequest("weather","GET","c","r","","",Map.of("location","Warszawa","mode","daily","date","tomorrow","days",1)));
  assertThat(result.success()).isTrue();assertThat(result.data()).containsKeys("location","values","units","source","retrievedAt");
  var urls=org.mockito.ArgumentCaptor.forClass(java.net.URI.class);verify(http,times(2)).get(urls.capture());assertThat(urls.getAllValues().get(1).toString()).contains("start_date=","timezone=auto","apparent_temperature_max");assertThat(json.writeValueAsString(result.data()).length()).isLessThan(2200);
 }
 @Test void invalidLocationAndModeNeverFetch(){var http=mock(InformationHttpClient.class);var weather=new WeatherTool(http);assertThat(weather.execute(new ToolRequest("weather","GET","c","r","","",Map.of())).success()).isFalse();assertThat(weather.execute(new ToolRequest("weather","GET","c","r","","",Map.of("location","Warsaw","mode","shell"))).success()).isFalse();verifyNoInteractions(http);}
 @Test void currencyPreservesPublicationDateAndDistinguishesReferenceFromMarket()throws Exception{
  var http=mock(InformationHttpClient.class);when(http.get(any())).thenReturn(new ObjectMapper().readTree("{\"code\":\"USD\",\"rates\":[{\"no\":\"200/A/NBP/2026\",\"effectiveDate\":\"2026-11-27\",\"mid\":3.91}]}"));
  var result=new ExchangeRateTool(http).execute(new ToolRequest("exchange","RATE","c","r","","",Map.of("currency","USD")));assertThat(result.success()).isTrue();assertThat(result.data()).containsEntry("effectiveDate","2026-11-27").containsEntry("rateKind","OFFICIAL_REFERENCE_NOT_LIVE_MARKET");
 }
}
