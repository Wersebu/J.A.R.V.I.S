package com.jarvis.core.google;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.*;
import com.jarvis.common.integration.*;
import com.jarvis.tools.*;
import com.jarvis.tools.integration.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
/** Owns readiness decisions; models never initiate extra consent for an already permitted read. */
@Component
public class GoogleIntegrationPolicy implements IntegrationAuthorizationPolicy,IntegrationStatusSource {
 private final GoogleOAuth oauth;private final UserDevice device;private final ObjectProvider<CompanionDocumentStore> store;private final ObjectProvider<CompanionEventSink> events;
 private final java.util.concurrent.locks.ReentrantLock[] locks=java.util.stream.IntStream.range(0,64).mapToObj(i->new java.util.concurrent.locks.ReentrantLock(true)).toArray(java.util.concurrent.locks.ReentrantLock[]::new);
 private final Map<String,Long> recentlyAuthorized=Collections.synchronizedMap(new LinkedHashMap<>());
 private final Map<String,String> attempts=Collections.synchronizedMap(new LinkedHashMap<>());
 public GoogleIntegrationPolicy(GoogleOAuth oauth,UserDevice device,ObjectProvider<CompanionDocumentStore> store,ObjectProvider<CompanionEventSink> events){this.oauth=oauth;this.device=device;this.store=store;this.events=events;}
 public Set<String> providers(){return Set.of("gmail","calendar");}
 private GoogleService service(String provider){return provider.equalsIgnoreCase("gmail")?GoogleService.GMAIL:GoogleService.CALENDAR;}
 public boolean supports(ToolRequest r){return providers().contains(r.toolName().toLowerCase(Locale.ROOT));}
 public IntegrationStatus status(String owner,String provider,String operation){return oauth.operationStatus(owner,service(provider),operation);}
 public void prepare(ToolRequest request){
  if(Set.of("STATUS","DISCONNECT").contains(request.operation().toUpperCase(Locale.ROOT)))return;
  String owner=CurrentUserContext.requireAuthenticatedUserId();var service=service(request.toolName());
  try{oauth.requireScopes(owner,service,service.requiredScopes(request.operation()));}
  catch(GoogleException failure){if(!failure.authorizationRequired())throw convert(failure);authorize(request,false);}
 }
 public boolean recoverRead(ToolRequest request,ToolResult failure){
  if(!Set.of("MISSING_SCOPE","AUTHORIZATION_REQUIRED","TOKEN_EXPIRED").contains(failure.errorCode()))return false;
  if(!Set.of("READ","SEARCH","UNREAD","THREAD","ATTACHMENT","CALENDARS","EVENTS","FREE","TASKS").contains(request.operation().toUpperCase(Locale.ROOT)))return false;
  authorize(request,true);return true;
 }
 private void authorize(ToolRequest request,boolean rejectedByApi){
  String owner=CurrentUserContext.requireAuthenticatedUserId();var service=service(request.toolName());var required=service.requiredScopes(request.operation());
  String identity=owner+"\n"+request.requestId()+"\n"+Objects.toString(request.conversationId(),"")+"\n"+Objects.toString(TaskExecutionContext.current(),"")+"\n"+service.provider()+"\n"+required.stream().sorted().toList();
  String id=UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
  String grantKey=owner+"\n"+service.provider()+"\n"+required.stream().sorted().toList();
  String observedAuthorization=oauth.authorizationRevision(owner,service);
  TaskExecutionContext.report("WAITING_FOR_AUTHORIZATION","Oczekiwanie na autoryzację "+service.label());
  try{
   var consentLock=locks[Math.floorMod((owner+service.provider()).hashCode(),locks.length)];
   try{if(!consentLock.tryLock(320,java.util.concurrent.TimeUnit.SECONDS))throw new GoogleException("AUTHORIZATION_TIMEOUT","Upłynął czas oczekiwania na autoryzację.");}
   catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new GoogleException("CANCELLED","Przerwano oczekiwanie na autoryzację.");}
   try{
    if(Thread.currentThread().isInterrupted())throw new GoogleException("CANCELLED","Przerwano oczekiwanie na autoryzację.");
    Long authorizedAt=recentlyAuthorized.get(grantKey);
    if(!rejectedByApi||!Objects.equals(observedAuthorization,oauth.authorizationRevision(owner,service))||authorizedAt!=null&&System.nanoTime()-authorizedAt<java.util.concurrent.TimeUnit.SECONDS.toNanos(320)){try{oauth.requireScopes(owner,service,required);return;}catch(GoogleException e){if(!e.authorizationRequired())throw e;}}
    var documents=store.getIfAvailable();String previous=attempts.get(id);
    if(previous==null&&documents!=null)previous=documents.find(owner,"integration-authorization",id).map(d->{try{return new com.fasterxml.jackson.databind.ObjectMapper().readTree(d.body()).path("status").asText();}catch(Exception e){return "ERROR";}}).orElse(null);
    if(previous!=null)throw new GoogleException("AUTHORIZATION_NOT_REPEATED","Nie ponawiam autoryzacji dla tej samej operacji. Pozostałe wyniki zostały zachowane.");
    update(owner,id,request,"WAITING_FOR_AUTHORIZATION");
    try{device.connectGoogle(owner,service,required);oauth.requireScopes(owner,service,required);synchronized(recentlyAuthorized){recentlyAuthorized.put(grantKey,System.nanoTime());while(recentlyAuthorized.size()>2048)recentlyAuthorized.remove(recentlyAuthorized.keySet().iterator().next());}update(owner,id,request,"COMPLETED");}
    catch(GoogleException failure){update(owner,id,request,failure.code().equals("AUTHORIZATION_CANCELLED")||failure.code().equals("CANCELLED")?"CANCELLED":"FAILED");throw failure;}
   }finally{consentLock.unlock();}
  }catch(GoogleException failure){throw convert(failure);}
  finally{TaskExecutionContext.report("RUNNING","Kontynuowanie polecenia po zakończeniu autoryzacji");}
 }
 private void update(String owner,String id,ToolRequest request,String state){
  synchronized(attempts){attempts.put(id,state);while(attempts.size()>2048)attempts.remove(attempts.keySet().iterator().next());}
  Map<String,Object> payload=new LinkedHashMap<>();payload.put("operationId",id);payload.put("provider",request.toolName());payload.put("operation",request.operation());payload.put("requestId",request.requestId());payload.put("conversationId",request.conversationId());payload.put("taskId",TaskExecutionContext.current());payload.put("status",state);payload.put("deadline",Instant.now().plusSeconds(320).toString());
  var documents=store.getIfAvailable();if(documents!=null)try{documents.put(new CompanionDocumentStore.Document(owner,"integration-authorization",id,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload),Instant.now()));}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("Cannot persist authorization state");}
  var journal=events.getIfAvailable();if(journal!=null)journal.record(owner,"integration.authorization.updated",TaskExecutionContext.current(),payload);
 }
 private IntegrationAccessException convert(GoogleException e){String message=switch(e.code()){
  case "MISSING_SCOPE"->"Nie przyznano uprawnień wymaganych przez tę operację. Pozostałe części polecenia mogą być kontynuowane.";
  case "AUTHORIZATION_REQUIRED","DISCONNECTED","TOKEN_EXPIRED"->"Ta integracja wymaga ponownego połączenia konta.";
  case "TEMPORARILY_UNAVAILABLE"->"Usługa Google jest chwilowo niedostępna. Ponowna autoryzacja nie jest teraz potrzebna.";
  default->e.getMessage();};return new IntegrationAccessException(e.code(),message,Map.of("integrationState",e.code(),"authorizationRequired",e.authorizationRequired(),"retryable",e.code().equals("TEMPORARILY_UNAVAILABLE")));}
}
