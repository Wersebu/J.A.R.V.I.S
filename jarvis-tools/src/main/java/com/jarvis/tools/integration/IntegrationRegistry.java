package com.jarvis.tools.integration;
import com.jarvis.common.integration.*;
import com.jarvis.tools.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
@Service
public class IntegrationRegistry {
 private final List<IntegrationStatusSource> sources;
 private final Map<String,IntegrationStatus> observed=Collections.synchronizedMap(new LinkedHashMap<>());
 public IntegrationRegistry(List<IntegrationStatusSource> sources){this.sources=List.copyOf(sources);}
 public List<IntegrationStatus> snapshot(String owner){
  var result=new LinkedHashMap<String,IntegrationStatus>();for(var source:sources)for(String provider:source.providers())try{result.put(provider,source.status(owner,provider,"READ"));}catch(RuntimeException failure){result.put(provider,new IntegrationStatus(provider,"READ",IntegrationState.ERROR,false,false,Set.of(),Set.of(),Set.of(),"STATUS_UNAVAILABLE",Instant.now()));}
  synchronized(observed){observed.forEach((key,status)->{if(key.startsWith(owner+"\n"))result.put(status.provider()+"/"+status.operation(),status);});}
  return List.copyOf(result.values());
 }
 public void observe(String owner,ToolResult result){
  String code=result.errorCode()==null?"":result.errorCode();IntegrationState state=null;
  if(Set.of("MISSING_SCOPE","TOKEN_EXPIRED","REFRESH_REQUIRED","AUTHORIZATION_REQUIRED","DISCONNECTED","TEMPORARILY_UNAVAILABLE").contains(code))state=IntegrationState.valueOf(code);
  if(Set.of("AUTHENTICATION_REQUIRED","MISSING_CREDENTIALS","NOT_CONNECTED","UNAUTHORIZED").contains(code))state=IntegrationState.AUTHORIZATION_REQUIRED;
  String provider=result.tool().toLowerCase(Locale.ROOT);
  if((provider.equals("pc")&&result.operation().toUpperCase(Locale.ROOT).startsWith("SPOTIFY"))||provider.startsWith("mcp_spotify_"))provider="spotify";
  boolean known=provider.equals("spotify")||sources.stream().anyMatch(source->source.providers().contains(result.tool().toLowerCase(Locale.ROOT)));
  if(state==null&&known&&result.success()){
   if(result.data().get("connected") instanceof Boolean connected)state=connected?IntegrationState.CONNECTED:IntegrationState.DISCONNECTED;
   else if(!Set.of("STATUS","CONNECT","DISCONNECT").contains(result.operation().toUpperCase(Locale.ROOT)))state=IntegrationState.CONNECTED;
  }
  if(state==null&&known&&!result.success()&&Set.of("API_DISABLED","PERMISSION_DENIED","API_ERROR","ERROR").contains(code))state=IntegrationState.ERROR;
  if(state==null)return;
  // Only typed provider failures change availability; a generic failed lookup never means disconnected.
  var status=new IntegrationStatus(provider,result.operation(),state,state!=IntegrationState.DISCONNECTED,false,Set.of(),Set.of(),Set.of(),code,Instant.now());
  synchronized(observed){String key=owner+"\n"+result.tool()+"/"+result.operation();observed.put(key,status);while(observed.size()>2048)observed.remove(observed.keySet().iterator().next());}
 }
 public void clear(String owner,String provider){synchronized(observed){observed.entrySet().removeIf(e->e.getKey().startsWith(owner+"\n")&&e.getValue().provider().equals(provider));}}
}
