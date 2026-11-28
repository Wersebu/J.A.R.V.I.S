package com.jarvis.inference;
import com.jarvis.common.ai.AIProviderException;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Core owns only its child process. Never signals an externally managed server. */
@Component
public class LlamaServerProcess {
 private final InferenceProperties properties;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
 private Process child;
 private final Deque<String> logs=new ArrayDeque<>();
 public LlamaServerProcess(InferenceProperties properties){this.properties=properties;}
 public boolean installed(){return Files.isExecutable(properties.executable());}
 public boolean healthy(){try{return http.send(HttpRequest.newBuilder(URI.create(properties.baseUrl()+"/health")).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();return false;}}
 public synchronized boolean alive(){return child!=null&&child.isAlive();}
 public synchronized List<String> logs(){return List.copyOf(logs);}
 public synchronized void start(Path model,String alias,Map<String,Object> p){
  if(!properties.managedProcess())throw new AIProviderException("PROCESS_MANAGEMENT_DISABLED: enable managed-process; do not also use systemd for llama-server");
  if(alive())throw new AIProviderException("ENGINE_ALREADY_RUNNING");
  if(!installed())throw new AIProviderException("NOT_INSTALLED");
  try(var socket=new java.net.ServerSocket()){socket.bind(new InetSocketAddress("127.0.0.1",properties.port()));}
  catch(Exception e){throw new AIProviderException("PORT_IN_USE: refusing to adopt another process",e);}
  var args=command(model,alias,p);
  try{logs.clear();child=new ProcessBuilder(args).redirectErrorStream(true).start();Process owned=child;
   Thread.startVirtualThread(()->{try(var reader=owned.inputReader()){String line;while((line=reader.readLine())!=null){synchronized(this){logs.addLast(line);while(logs.size()>200)logs.removeFirst();}}}catch(Exception ignored){}});
  }catch(Exception e){throw new AIProviderException("PROCESS_START_FAILED",e);}
 }
 List<String> command(Path model,String alias,Map<String,Object> p){
  var args=new ArrayList<>(List.of(properties.executable().toString(),"--host","127.0.0.1","--port",String.valueOf(properties.port()),"--no-context-shift","--reasoning-format","auto","--model",model.toString(),"--alias",alias,"--ctx-size",p.getOrDefault("contextSize",4096).toString(),"--parallel",p.getOrDefault("parallelSlots",1).toString(),"--n-gpu-layers",p.getOrDefault("gpuLayers",0).toString()));
  args.add(Boolean.FALSE.equals(p.get("jinja"))?"--no-jinja":"--jinja");
  Map<String,String> flags=Map.of("cpuThreads","--threads","batchSize","--batch-size","microBatchSize","--ubatch-size","cacheTypeK","--cache-type-k","cacheTypeV","--cache-type-v","flashAttention","--flash-attn","mmproj","--mmproj","chatTemplate","--chat-template-file");
  flags.forEach((k,v)->{if(p.containsKey(k)){args.add(v);args.add(p.get(k).toString());}});
  for(String key:List.of("reasoning","reasoningEffort"))if(p.containsKey(key)){args.add(key.equals("reasoning")?"--reasoning":"--reasoning-effort");args.add(p.get(key).toString());}
  if(((Number)p.getOrDefault("gpuLayers",0)).intValue()==0){args.add("--device");args.add("none");args.add("--no-mmproj-offload");}
  if(Boolean.FALSE.equals(p.get("memoryMapping")))args.add("--no-mmap");
  return List.copyOf(args);
 }
 public void awaitReady(Duration timeout){long end=System.nanoTime()+timeout.toNanos();while(System.nanoTime()<end){if(!alive()){boolean oom=logs().stream().anyMatch(line->line.toLowerCase(Locale.ROOT).contains("out of memory")||line.toLowerCase(Locale.ROOT).contains("cudaerrormemoryallocation"));throw new AIProviderException(oom?"OUT_OF_MEMORY: model load failed":"MODEL_LOAD_FAILED: llama-server exited; inspect diagnostic logs");}if(healthy())return;try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AIProviderException("CANCELLED",e);}}throw new AIProviderException("MODEL_LOAD_TIMEOUT");}
 @PreDestroy public synchronized void stop(){if(child==null)return;Process owned=child;owned.destroy();try{if(!owned.waitFor(10,TimeUnit.SECONDS)){owned.destroyForcibly();if(!owned.waitFor(5,TimeUnit.SECONDS))throw new AIProviderException("PROCESS_STOP_FAILED");}}catch(InterruptedException e){owned.destroyForcibly();Thread.currentThread().interrupt();throw new AIProviderException("CANCELLED",e);}finally{if(!owned.isAlive())child=null;}}
}
