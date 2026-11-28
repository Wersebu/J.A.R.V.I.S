package com.jarvis.core.google;
import com.fasterxml.jackson.databind.*;
public final class GoogleApiErrors {
 private GoogleApiErrors(){}
 public static GoogleException failure(int status,String body){
  String reason="";try{var node=new ObjectMapper().readTree(body);reason=node.path("error").path("status").asText("");if(node.path("error").path("message").asText("").equals("Request had insufficient authentication scopes."))reason="ACCESS_TOKEN_SCOPE_INSUFFICIENT";for(var e:node.path("error").path("errors")){String r=e.path("reason").asText();if(java.util.Set.of("insufficientPermissions","accessNotConfigured","rateLimitExceeded","userRateLimitExceeded","quotaExceeded").contains(r)){reason=r;break;}}for(var detail:node.path("error").path("details")){String r=detail.path("reason").asText();if(r.equals("ACCESS_TOKEN_SCOPE_INSUFFICIENT")||r.equals("SERVICE_DISABLED"))reason=r;}}catch(Exception ignored){}
  if(status==401)return new GoogleException("AUTHORIZATION_REQUIRED","Google odrzucił poświadczenia po próbie odświeżenia połączenia.");
  if(status==403&&(reason.equals("insufficientPermissions")||reason.equals("ACCESS_TOKEN_SCOPE_INSUFFICIENT")))return new GoogleException("MISSING_SCOPE","Brakuje uprawnienia Google wymaganego do wykonania tej operacji.");
  if(status==403&&(reason.equals("accessNotConfigured")||reason.equals("SERVICE_DISABLED")))return new GoogleException("API_DISABLED","Usługa API Google nie jest włączona w projekcie. Ponowna autoryzacja tego nie naprawi.");
  if(status==429||status>=500||java.util.Set.of("rateLimitExceeded","userRateLimitExceeded","quotaExceeded").contains(reason))return new GoogleException("TEMPORARILY_UNAVAILABLE","Usługa Google jest chwilowo niedostępna lub ogranicza liczbę zapytań. Pozostałe części polecenia mogą być kontynuowane.");
  if(status==404)return new GoogleException("NOT_FOUND","Nie znaleziono wskazanego elementu w usłudze Google.");
  return new GoogleException(status==403?"PERMISSION_DENIED":"API_ERROR",status==403?"Google odmówił dostępu do tego zasobu. To nie potwierdza braku zakresu OAuth.":"Żądanie do Google nie powiodło się (HTTP "+status+").");
 }
}
