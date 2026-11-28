package com.jarvis.inference;
import com.jarvis.common.ai.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;
@Service
public class InferenceDiagnostics implements InferenceDiagnosticsControl {
 private final InferenceEngineManager manager;private final InferenceProperties properties;private final ObjectProvider<AIProvider> providers;private final EngineAccessGate gate;
 public InferenceDiagnostics(InferenceEngineManager manager,InferenceProperties properties,ObjectProvider<AIProvider> providers,EngineAccessGate gate){this.manager=manager;this.properties=properties;this.providers=providers;this.gate=gate;}
 private volatile Map<String,Object> installState=Map.of("status","IDLE");
 private volatile Process installChild;
 private final java.util.concurrent.ExecutorService installer=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"llama-installer");t.setDaemon(true);return t;});
 @org.springframework.beans.factory.annotation.Value("${jarvis.inference.engines.installer:/opt/jarvis/scripts/install-llamacpp.sh}") private Path installerPath;
 private org.springframework.beans.factory.ObjectProvider<com.jarvis.common.companion.CompanionEventSink> events;
 @org.springframework.beans.factory.annotation.Autowired public void events(org.springframework.beans.factory.ObjectProvider<com.jarvis.common.companion.CompanionEventSink> value){events=value;}
 public List<String> logs(){return manager.logs();}
 public synchronized Map<String,Object> install(){
  if(!System.getProperty("os.name").equalsIgnoreCase("Linux")||installerPath==null||!installerPath.isAbsolute()||!Files.isRegularFile(installerPath))throw new AIProviderException("INSTALLER_UNAVAILABLE: install scripts/install-llamacpp.sh on Ubuntu first");
  if(installState.get("status").equals("INSTALLING"))throw new AIProviderException("INSTALLATION_BUSY");
  String owner=com.jarvis.common.auth.CurrentUserContext.requireAuthenticatedUserId(),operation=UUID.randomUUID().toString();
  updateInstall(owner,operation,"INSTALLING","");
  installer.execute(()->{try{
   installChild=new ProcessBuilder("/usr/bin/bash",installerPath.toString()).redirectErrorStream(true).start();
   Process child=installChild;var output=new java.util.concurrent.atomic.AtomicReference<>("");
   Thread reader=Thread.startVirtualThread(()->{try(var r=child.inputReader()){String line;while((line=r.readLine())!=null)output.set(line.substring(0,Math.min(line.length(),1000)));}catch(Exception ignored){}});
   if(!child.waitFor(30,java.util.concurrent.TimeUnit.MINUTES)){child.descendants().forEach(ProcessHandle::destroy);child.destroyForcibly();throw new AIProviderException("INSTALLATION_TIMEOUT");}
   reader.join(1000);
   if(child.exitValue()!=0)throw new AIProviderException("INSTALLATION_FAILED: "+output.get());
   updateInstall(owner,operation,"COMPLETED","");
  }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();updateInstall(owner,operation,"ERROR",e.getMessage()==null?"INSTALLATION_FAILED":e.getMessage());}finally{installChild=null;}});
  return installState;
 }
 private void updateInstall(String owner,String operation,String state,String error){installState=Map.of("operationId",operation,"status",state,"error",error);var sink=events==null?null:events.getIfAvailable();if(sink!=null)sink.record(owner,"inference.installation.updated",null,installState);}
 @jakarta.annotation.PreDestroy public void close(){Process child=installChild;if(child!=null){child.descendants().forEach(ProcessHandle::destroy);child.destroyForcibly();}installer.shutdownNow();}

 public Map<String,Object> installation(){return Map.of("engine","llamacpp","installed",Files.isExecutable(properties.executable()),"managedProcess",properties.managedProcess(),"installer","scripts/install-llamacpp.sh","requires","Ubuntu service-owner write access to /opt/jarvis/engines and models; sudo for missing apt/CUDA dependencies","operation",installState,"remoteInstallSupported",installerPath!=null&&Files.isRegularFile(installerPath));}
 public Map<String,Object> benchmark(){
  var selection=manager.selection();String operation=UUID.randomUUID().toString();
  try(var residencyLease=gate.enter(selection.engine())){
   var provider=providers.orderedStream().filter(p->p.provider().equals(selection.engine())).findFirst().orElseThrow(()->new AIProviderException("ENGINE_UNAVAILABLE"));
   Brain brain=new Brain(BrainType.FAST,selection.engine(),selection.model(),"Inference benchmark","",0,ReasoningLevel.LOW,selection.configurationId());
   List<ModelMessage> prompt=List.of(ModelMessage.system("Answer concisely. Do not call tools."),ModelMessage.user("Explain why the sky appears blue in exactly two short sentences."));
   String residency=manager.residency();long[] first={0};var before=manager.resources();long start=System.nanoTime();
   ModelResponse result=provider.streamChat(brain,prompt,List.of(),Map.of(),AIJobType.CHAT,e->{if(first[0]==0&&e.type()==ModelStreamEvent.Type.FIRST_TOKEN)first[0]=System.nanoTime();});
   long finish=System.nanoTime();var report=new LinkedHashMap<String,Object>();report.put("operationId",operation);report.put("selection",selection);report.put("mode",residency.equals("LOADED")?"WARM_RESIDENT":residency.equals("NOT_LOADED")?"COLD_MODEL_LOAD":"RESIDENCY_UNVERIFIED");report.put("promptId","sky-two-sentences-v1");report.put("observedAt",Instant.now().toString());report.put("usage",result.usage());report.put("totalMs",(finish-start)/1e6);report.put("timeToFirstTokenMs",first[0]==0?null:(first[0]-start)/1e6);report.put("generationTokensPerSecond",first[0]==0||result.usage().completionTokens()==0?null:result.usage().completionTokens()*1e9/Math.max(1,finish-first[0]));report.put("promptProcessingTokensPerSecond",null);report.put("resourcesBefore",before);report.put("resourcesAfter",manager.resources());report.put("toolCallingTest","NOT_RUN");report.put("structuredOutputTest","NOT_RUN");report.put("visionTest","NOT_RUN");report.put("parallelRequestsTest","NOT_RUN: conservative one-slot policy");report.put("qualityScore",null);return report;
  }
 }
}
