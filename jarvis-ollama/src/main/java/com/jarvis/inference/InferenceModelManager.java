package com.jarvis.inference;
import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.CompanionEventSink;
import com.jarvis.ollama.OllamaProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.io.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
/** Bounded owner-scoped downloads; HF URLs are constructed, never supplied by the client. */
@Service
public class InferenceModelManager implements InferenceModelOperations {
 private final InferenceProperties properties;private final InferenceEngineManager manager;private final OllamaProperties ollama;private final ObjectMapper mapper;private final ObjectProvider<CompanionEventSink> events;private final EngineAccessGate gate;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
 private final ExecutorService workers=Executors.newFixedThreadPool(1,r->{Thread t=new Thread(r,"model-download");t.setDaemon(true);return t;});
 private final Map<String,Operation> operations=new LinkedHashMap<>();
 private static final class Operation {final String id=UUID.randomUUID().toString(),owner,model,engine;volatile String status="QUEUED",error="";volatile long bytes,total=-1,startBytes,revision,start=System.nanoTime();volatile boolean cancelled;volatile Future<?> future;volatile InputStream input;Operation(String owner,String engine,String model){this.owner=owner;this.engine=engine;this.model=model;}}
 public InferenceModelManager(InferenceProperties p,InferenceEngineManager m,OllamaProperties o,ObjectMapper mapper,ObjectProvider<CompanionEventSink> events,EngineAccessGate gate){properties=p;manager=m;ollama=o;this.mapper=mapper;this.events=events;this.gate=gate;}
 private String owner(){return CurrentUserContext.requireAuthenticatedUserId();}
 public synchronized List<Map<String,Object>> operations(){String owner=owner();return operations.values().stream().filter(o->o.owner.equals(owner)).map(this::snapshot).toList();}
 private Map<String,Object> snapshot(Operation o){var s=new LinkedHashMap<String,Object>();s.put("operationId",o.id);s.put("revision",o.revision);s.put("engine",o.engine);s.put("model",o.model);s.put("status",o.status);s.put("downloadedBytes",o.bytes);s.put("totalBytes",o.total<0?null:o.total);double speed=Math.max(0,o.bytes-o.startBytes)*1e9/Math.max(1,System.nanoTime()-o.start);s.put("bytesPerSecond",speed);s.put("percent",o.total<=0?null:Math.min(100d,o.bytes*100d/o.total));s.put("etaSeconds",o.total<0||speed<=0?null:Math.max(0,(o.total-o.bytes)/speed));s.put("error",o.error);return s;}
 private void publish(Operation o){o.revision++;var sink=events.getIfAvailable();if(sink!=null)sink.record(o.owner,"inference.download.updated",null,snapshot(o));}
 public synchronized Map<String,Object> download(Download r){
  if(r==null||r.engine()==null)throw new AIProviderException("INVALID_DOWNLOAD");
  if(!Set.of("ollama","llamacpp").contains(r.engine()))throw new AIProviderException("UNKNOWN_ENGINE");
  if(r.engine().equals("llamacpp")){
   if(r.repository()==null||!r.repository().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||r.revision()==null||!r.revision().matches("[a-fA-F0-9]{40}")||r.file()==null||!r.file().matches("[A-Za-z0-9_.-]+\\.gguf")||r.file().contains("..")||r.sha256()==null||!r.sha256().matches("[a-fA-F0-9]{64}"))throw new AIProviderException("INVALID_HF_DOWNLOAD: exact commit, basename GGUF and SHA256 required");
  }else if(r.file()==null||!r.file().matches("[A-Za-z0-9_./:-]{1,200}"))throw new AIProviderException("INVALID_OLLAMA_MODEL");
  if(operations.values().stream().anyMatch(o->Set.of("QUEUED","DOWNLOADING","VERIFYING").contains(o.status)))throw new AIProviderException("DOWNLOAD_BUSY");
  while(operations.size()>=128)operations.remove(operations.keySet().iterator().next());
  Operation o=new Operation(owner(),r.engine(),r.file());operations.put(o.id,o);o.future=workers.submit(()->run(o,r));return snapshot(o);
 }
 private void run(Operation o,Download r){
  try{o.status="DOWNLOADING";publish(o);
   if(r.engine().equals("ollama")){ollamaPull(o,r.file());}
   else{hf(o,r);}
   if(o.cancelled)throw new InterruptedException();o.status="COMPLETED";
  }catch(Exception e){o.status=o.cancelled||e instanceof InterruptedException?"CANCELLED":"ERROR";o.error=o.status.equals("CANCELLED")?"":e.getMessage();}
  finally{o.input=null;publish(o);}
 }
 private void hf(Operation o,Download r)throws Exception{
  Files.createDirectories(properties.modelDirectory());Path root=properties.modelDirectory().toRealPath();Path target=root.resolve(r.file());Path partial=root.resolve(r.file()+".part");
  if(Files.isSymbolicLink(target)||Files.isSymbolicLink(partial))throw new AIProviderException("INVALID_MODEL_PATH");
  if(Files.exists(target)){verify(target,r.sha256());o.bytes=Files.size(target);o.total=o.bytes;return;}
  // Immutable commit URL + requested digest make a partial resumable across Core restarts.
  long offset=Files.exists(partial)?Files.size(partial):0;
  URI url=URI.create("https://huggingface.co/"+r.repository()+"/resolve/"+r.revision()+"/"+r.file());
  var builder=HttpRequest.newBuilder(url).timeout(Duration.ofHours(6)).GET();if(offset>0)builder.header("Range","bytes="+offset+"-");
  var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofInputStream());
  try(var input=response.body()){
   o.input=input;if(response.statusCode()!=200&&response.statusCode()!=206)throw new AIProviderException("DOWNLOAD_HTTP_"+response.statusCode());
   if(response.statusCode()==206&&!response.headers().firstValue("Content-Range").orElse("").startsWith("bytes "+offset+"-"))throw new AIProviderException("INVALID_CONTENT_RANGE");
   if(response.statusCode()==200)offset=0;
   long length=response.headers().firstValueAsLong("Content-Length").orElse(-1);o.total=length<0?-1:length+offset;o.bytes=offset;o.startBytes=offset;
   if(o.total>0&&Files.getFileStore(root).getUsableSpace()<o.total-offset+1024L*1024*1024)throw new AIProviderException("INSUFFICIENT_DISK_SPACE");
   try(var output=Files.newOutputStream(partial,StandardOpenOption.CREATE,offset>0?StandardOpenOption.APPEND:StandardOpenOption.TRUNCATE_EXISTING)){
    byte[] buffer=new byte[1024*1024];long last=0;int n;
    while((n=input.read(buffer))!=-1){if(o.cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();output.write(buffer,0,n);o.bytes+=n;if(System.nanoTime()-last>TimeUnit.SECONDS.toNanos(1)){publish(o);last=System.nanoTime();}}
   }
  }
  if(o.total>=0&&o.bytes!=o.total)throw new AIProviderException("INCOMPLETE_DOWNLOAD");
  o.status="VERIFYING";publish(o);verify(partial,r.sha256());if(o.cancelled)throw new InterruptedException();Files.move(partial,target,StandardCopyOption.ATOMIC_MOVE);
 }
 private void ollamaPull(Operation o,String model)throws Exception{
  // Pull does not request loading; use the same residency gate to avoid a concurrent engine switch.
  try(var lease=gate.enter("ollama")){
   var response=http.send(HttpRequest.newBuilder(URI.create(ollama.baseUrl()+"/api/pull")).timeout(Duration.ofHours(6)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("model",model,"stream",true)))).build(),HttpResponse.BodyHandlers.ofInputStream());
   try(var input=response.body();var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))){o.input=input;if(response.statusCode()!=200)throw new AIProviderException("DOWNLOAD_HTTP_"+response.statusCode());String line;boolean success=false;long last=0;Map<String,Long> blobs=new HashMap<>();while((line=reader.readLine())!=null){if(o.cancelled)throw new InterruptedException();var frame=mapper.readTree(line);if(frame.has("error"))throw new AIProviderException("OLLAMA_PULL_FAILED");if(frame.has("completed")&&frame.has("digest")){blobs.put(frame.get("digest").asText(),frame.get("completed").asLong());o.bytes=blobs.values().stream().mapToLong(Long::longValue).sum();}if(frame.path("status").asText().equals("success"))success=true;if(System.nanoTime()-last>TimeUnit.SECONDS.toNanos(1)){publish(o);last=System.nanoTime();}}if(!success)throw new AIProviderException("INCOMPLETE_DOWNLOAD");o.total=o.bytes;}
  }
 }
 private void verify(Path path,String digest)throws Exception{var hash=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(path)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();hash.update(b,0,n);}}if(!HexFormat.of().formatHex(hash.digest()).equalsIgnoreCase(digest))throw new AIProviderException("CHECKSUM_MISMATCH");}
 public synchronized Map<String,Object> cancel(String id){Operation o=operations.get(id);if(o==null||!o.owner.equals(owner()))throw new AIProviderException("OPERATION_NOT_FOUND");if(Set.of("COMPLETED","ERROR","CANCELLED").contains(o.status))return snapshot(o);o.cancelled=true;if(o.future!=null)o.future.cancel(true);if(o.input!=null)try{o.input.close();}catch(Exception ignored){}o.status="CANCELLED";publish(o);return snapshot(o);}
 public Map<String,Object> importModel(String path,String sha256){owner();Path file=manager.validateModel(path);try{if(sha256==null||!sha256.matches("[a-fA-F0-9]{64}"))throw new AIProviderException("SHA256_REQUIRED");verify(file,sha256);return Map.of("model",path,"status","AVAILABLE","sha256",sha256.toLowerCase(Locale.ROOT),"copied",false);}catch(Exception e){throw new AIProviderException("MODEL_IMPORT_FAILED",e);}}
 public void delete(String engine,String model){owner();try(var exclusive=gate.exclusive()){
  var selected=manager.selection();if(selected.engine().equals(engine)&&selected.model().equals(model))throw new AIProviderException("ACTIVE_MODEL_CANNOT_BE_DELETED");
  if(engine.equals("llamacpp")){Path path=manager.validateModel(model);if(path.getFileName().toString().matches(".*-\\d{5}-of-\\d{5}\\.gguf"))throw new AIProviderException("SHARDED_DELETE_REQUIRES_OPERATOR: no partial deletion");Files.delete(path);}
  else if(engine.equals("ollama")){var response=http.send(HttpRequest.newBuilder(URI.create(ollama.baseUrl()+"/api/delete")).timeout(Duration.ofSeconds(30)).header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("model",model)))).build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()!=200)throw new AIProviderException("MODEL_DELETE_FAILED");}
  else throw new AIProviderException("UNKNOWN_ENGINE");
 }catch(java.io.IOException|InterruptedException e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new AIProviderException("MODEL_DELETE_FAILED",e);}}
 @PreDestroy public void close(){synchronized(this){for(Operation o:operations.values()){o.cancelled=true;if(o.future!=null)o.future.cancel(true);if(o.input!=null)try{o.input.close();}catch(Exception ignored){}}}workers.shutdownNow();}
}
