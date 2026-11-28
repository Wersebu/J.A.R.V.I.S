package com.jarvis.tools.information;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
/** Fixed public data origins, bounded body/time, no redirects or user-controlled destinations. */
@Service
public class InformationHttpClient {
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 private final ObjectMapper json=new ObjectMapper();
 private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"information-http-deadline");t.setDaemon(true);return t;});
 public JsonNode get(URI uri){
  if(!"https".equals(uri.getScheme())||!Set.of("api.open-meteo.com","geocoding-api.open-meteo.com","api.nbp.pl").contains(uri.getHost())||uri.getUserInfo()!=null||uri.getPort()!=-1)throw new IllegalArgumentException("INVALID_INFORMATION_ORIGIN");
  try{var response=http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept","application/json").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
   try(var body=response.body()){var alarm=DEADLINES.schedule(()->{try{body.close();}catch(Exception ignored){}},10,TimeUnit.SECONDS);try{
    if(response.statusCode()!=200)throw new IllegalStateException("INFORMATION_HTTP_"+response.statusCode());byte[] bytes=body.readNBytes(262145);if(bytes.length>262144)throw new IllegalStateException("INFORMATION_RESPONSE_TOO_LARGE");var result=json.readTree(bytes);if(result==null||!result.isObject())throw new IllegalStateException("INVALID_INFORMATION_RESPONSE");return result;
   }finally{alarm.cancel(false);}}
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new IllegalStateException("INFORMATION_FETCH_FAILED",e);}
 }
}
