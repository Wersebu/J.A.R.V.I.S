package com.jarvis.tools.information;
import com.jarvis.tools.*;
import com.jarvis.tools.schema.*;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.util.*;
/** Official NBP reference rate in PLN; not a live retail/trading quote. */
@Service
public class ExchangeRateTool implements JarvisTool,ToolSchemaProvider {
 private final InformationHttpClient http;
 public ExchangeRateTool(InformationHttpClient http){this.http=http;}
 public String getName(){return "exchange";}
 public String getDescription(){return "Read the latest published official NBP average exchange rate for USD/EUR/etc against PLN. Includes effective publication date. This is a reference rate, not a real-time market or bank buy/sell quote. Use web search for other pairs or trading quotes.";}
 public ToolDefinition definition(){return new ToolDefinition(getName(),getDescription(),List.of(new ToolOperationDefinition("RATE","Latest published NBP table A rate in PLN",List.of(new ToolArgumentDefinition("currency","string",true,"Three-letter currency code, e.g. USD")),false,ToolSafetyLevel.READ)));}
 public ToolResult execute(ToolRequest r){try{
  if(!"RATE".equalsIgnoreCase(r.operation()))throw new IllegalArgumentException("UNKNOWN_OPERATION");String code=Objects.toString(r.arguments().get("currency"),"").toUpperCase(Locale.ROOT);if(!code.matches("[A-Z]{3}")||code.equals("PLN"))throw new IllegalArgumentException("INVALID_CURRENCY");
  URI source=URI.create("https://api.nbp.pl/api/exchangerates/rates/a/"+code.toLowerCase(Locale.ROOT)+"/?format=json");var body=http.get(source);var rate=body.path("rates").path(0);
  if(!body.path("code").asText().equals(code)||!rate.path("mid").isNumber()||rate.path("mid").asDouble()<=0||rate.path("effectiveDate").asText().isBlank())throw new IllegalStateException("INVALID_RATE_RESPONSE");
  return new ToolResult(true,getName(),"RATE",r.requestId(),r.conversationId(),false,List.of(),"Published NBP reference rate",Map.of("base",code,"quote","PLN","rate",rate.path("mid").decimalValue(),"effectiveDate",rate.path("effectiveDate").asText(),"table",rate.path("no").asText(),"source",source.toString(),"retrievedAt",Instant.now().toString(),"rateKind","OFFICIAL_REFERENCE_NOT_LIVE_MARKET"),"","",false,"");
 }catch(Exception e){return new ToolResult(false,getName(),r.operation(),r.requestId(),r.conversationId(),false,List.of(),"Exchange rate unavailable",Map.of(),"EXCHANGE_UNAVAILABLE","No verified rate; do not invent a value",false,"");}}
}
