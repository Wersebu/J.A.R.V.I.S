package com.jarvis.tools.information;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.jarvis.tools.*;
import com.jarvis.tools.schema.*;
import org.springframework.stereotype.Service;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
/** Compact Open-Meteo model weather and forecasts. No scraped weather pages. */
@Service
public class WeatherTool implements JarvisTool,ToolSchemaProvider {
 private final InformationHttpClient http;
 public WeatherTool(InformationHttpClient http){this.http=http;}
 public String getName(){return "weather";}
 public String getDescription(){return "Read current weather or hourly/daily forecast for a location, including tomorrow. Prefer this to web search for weather. Uses Open-Meteo geocoding and forecasts; location/timezone/units/source/time are included. If location is missing, resolve it from conversation or ask.";}
 public ToolDefinition definition(){return new ToolDefinition(getName(),getDescription(),List.of(new ToolOperationDefinition("GET","Fetch compact weather data. mode: current, hourly or daily; date: ISO date or tomorrow (daily/hourly only).",List.of(new ToolArgumentDefinition("location","string",true,"City, optionally country, e.g. Warszawa"),new ToolArgumentDefinition("mode","string",false,"current (default), hourly, daily"),new ToolArgumentDefinition("date","string",false,"YYYY-MM-DD or tomorrow"),new ToolArgumentDefinition("days","integer",false,"1..7 daily forecast days; default 2"),new ToolArgumentDefinition("hours","integer",false,"1..24 hourly rows; default 12")),false,ToolSafetyLevel.READ)));}
 public ToolResult execute(ToolRequest request){try{
  if(!"GET".equalsIgnoreCase(request.operation()))throw new IllegalArgumentException("UNKNOWN_OPERATION");
  String location=Objects.toString(request.arguments().get("location"),"").strip();if(location.isBlank()||location.length()>160)throw new IllegalArgumentException("LOCATION_REQUIRED");
  String mode=Objects.toString(request.arguments().getOrDefault("mode","current"),"").toLowerCase(Locale.ROOT);if(!Set.of("current","hourly","daily").contains(mode))throw new IllegalArgumentException("INVALID_MODE");
  int days=integer(request,"days",2,7),hours=integer(request,"hours",12,24);
  URI geocoding=URI.create("https://geocoding-api.open-meteo.com/v1/search?name="+URLEncoder.encode(location,StandardCharsets.UTF_8)+"&count=5&language=en&format=json");
  var places=http.get(geocoding).path("results");if(!places.isArray()||places.isEmpty())throw new IllegalArgumentException("LOCATION_NOT_FOUND");
  var place=places.get(0);double lat=place.path("latitude").asDouble(Double.NaN),lon=place.path("longitude").asDouble(Double.NaN);if(!Double.isFinite(lat)||!Double.isFinite(lon))throw new IllegalArgumentException("INVALID_LOCATION");
  String zone=place.path("timezone").asText("UTC");LocalDate today=LocalDate.now(ZoneId.of(zone));String date=Objects.toString(request.arguments().get("date"),"");
  String range=mode.equals("hourly")?"&forecast_hours="+hours:"&forecast_days="+days;if(!date.isBlank()){
   LocalDate start=date.equalsIgnoreCase("today")?today:date.equalsIgnoreCase("tomorrow")?today.plusDays(1):LocalDate.parse(date);if(mode.equals("current")&&!start.equals(today))throw new IllegalArgumentException("DATE_REQUIRES_FORECAST_MODE");if(start.isBefore(today)||start.isAfter(today.plusDays(15)))throw new IllegalArgumentException("DATE_OUT_OF_FORECAST_RANGE");
   LocalDate end=start.plusDays(mode.equals("daily")?days-1:0);if(end.isAfter(today.plusDays(15)))throw new IllegalArgumentException("DATE_OUT_OF_FORECAST_RANGE");if(!mode.equals("current"))range="&start_date="+start+"&end_date="+end;
  }
  String variables=mode.equals("daily")?"daily=weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_direction_10m_dominant":mode+"=temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m,wind_direction_10m";
  URI source=URI.create("https://api.open-meteo.com/v1/forecast?latitude="+lat+"&longitude="+lon+"&timezone=auto&"+variables+range);
  var forecast=http.get(source);if(forecast.path("error").asBoolean()||!forecast.has(mode))throw new IllegalStateException("WEATHER_DATA_UNAVAILABLE");
  var values=forecast.path(mode).deepCopy();if(!mode.equals("current")){
   int max=mode.equals("hourly")?hours:days;var fields=values.fields();while(fields.hasNext()){var f=fields.next();if(f.getValue().isArray()){var array=(ArrayNode)f.getValue();while(array.size()>max)array.remove(array.size()-1);}}
  }
  var data=new LinkedHashMap<String,Object>();data.put("location",Map.of("name",place.path("name").asText(location),"country",place.path("country").asText(),"latitude",lat,"longitude",lon,"timezone",forecast.path("timezone").asText(zone),"resolvedFrom",location));data.put("mode",mode);data.put("values",values);data.put("units",forecast.path(mode+"_units"));data.put("source",source.toString());data.put("geocodingSource",geocoding.toString());data.put("retrievedAt",Instant.now().toString());data.put("dataKind","MODEL_WEATHER_NOT_STATION_OBSERVATION");
  return new ToolResult(true,getName(),"GET",request.requestId(),request.conversationId(),false,List.of(),"Weather retrieved",data,"","",false,"");
 }catch(Exception e){return new ToolResult(false,getName(),request.operation(),request.requestId(),request.conversationId(),false,List.of(),"Weather unavailable",Map.of(),"WEATHER_UNAVAILABLE",e instanceof IllegalArgumentException?e.getMessage():"Weather service unavailable; do not invent conditions",false,"");}}
 private int integer(ToolRequest r,String key,int fallback,int max){Object value=r.arguments().getOrDefault(key,fallback);if(!(value instanceof Number n)||n.doubleValue()!=n.intValue()||n.intValue()<1||n.intValue()>max)throw new IllegalArgumentException("INVALID_"+key.toUpperCase(Locale.ROOT));return n.intValue();}
}
