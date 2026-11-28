package com.jarvis.inference;
import com.fasterxml.jackson.databind.*;
import com.jarvis.common.ai.*;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.common.companion.CompanionEventSink;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.ollama.OllamaProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Transactional global default selection, preserving existing legacy Ollama configuration. */
@Service
public class InferenceEngineManager implements InferenceControl {
 private final InferenceProperties properties;private final ObjectMapper mapper;private final ActiveModelService ollama;
 private final OllamaProperties ollamaProperties;private final EngineAccessGate gate;private final LlamaServerProcess process;
 private final ObjectProvider<CompanionEventSink> events;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
 private volatile Selection selected;
 private final Map<String,Map<String,Object>> savedParameters=new java.util.concurrent.ConcurrentHashMap<>();
 private String modelKey(String engine,String model){return engine+":"+model;}
 private Path profilesFile(){return properties.configurationFile().resolveSibling(properties.configurationFile().getFileName()+".models.json");}
 private void restoreProfiles() throws Exception {
  if(Files.exists(profilesFile()))savedParameters.putAll(mapper.readValue(profilesFile().toFile(),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Map<String,Object>>>(){}));
 }
 private void saveProfile(Selection next) throws Exception {
  var updated=new LinkedHashMap<>(savedParameters);updated.put(modelKey(next.engine(),next.model()),next.parameters());
  Path path=profilesFile().toAbsolutePath();Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"profiles-",".tmp");
  try{Files.write(temp,mapper.writeValueAsBytes(updated));Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);savedParameters.clear();savedParameters.putAll(updated);}finally{Files.deleteIfExists(temp);}
 }

 private volatile String status="READY",error="",lastOperationId="";
 private final java.util.concurrent.atomic.AtomicLong revision=new java.util.concurrent.atomic.AtomicLong();
 private volatile Map<String,Object> lastMetrics=Map.of();
 private volatile int unhealthySamples;
 private volatile int observedContextPerSlot;
 @Override public int contextPerRequest(Selection snapshot){int configured=InferenceControl.super.contextPerRequest(snapshot);var active=selected;return active!=null&&active.configurationId().equals(snapshot.configurationId())&&observedContextPerSlot>0?Math.min(configured,observedContextPerSlot):configured;}
 private void observeLimits(Map<String,Object> parameters){
  observedContextPerSlot=0;
  try{var props=json(properties.baseUrl()+"/props",null);int slots=props.path("total_slots").asInt(0);int context=props.path("default_generation_settings").path("n_ctx").asInt(0);
   if(slots>0&&slots!=((Number)parameters.getOrDefault("parallelSlots",1)).intValue())throw new AIProviderException("RUNTIME_SLOT_MISMATCH");
   if(context>0)observedContextPerSlot=context;
  }catch(AIProviderException failure){if(failure.getMessage().equals("RUNTIME_SLOT_MISMATCH"))throw failure;}
 }

 @org.springframework.scheduling.annotation.Scheduled(fixedDelay=5000)
 public void monitor(){var s=selected;if(s==null||!s.engine().equals("llamacpp")||!status.equals("READY"))return;if(process.alive()&&process.healthy()){unhealthySamples=0;return;}if(++unhealthySamples>=3){status="ERROR";error=process.alive()?"ENGINE_UNHEALTHY: explicit restart required":"ENGINE_PROCESS_EXITED: inspect logs; explicit restart required";}}

 public InferenceEngineManager(InferenceProperties properties,ObjectMapper mapper,ActiveModelService ollama,OllamaProperties ollamaProperties,EngineAccessGate gate,LlamaServerProcess process,ObjectProvider<CompanionEventSink> events){this.properties=properties;this.mapper=mapper;this.ollama=ollama;this.ollamaProperties=ollamaProperties;this.gate=gate;this.process=process;this.events=events;}
 @PostConstruct public void restore(){
  selected=new Selection("legacy-ollama","ollama",ollama.activeModel(),Map.of());
  try{restoreProfiles();}catch(Exception e){status="ERROR";error="MODEL_PROFILES_RESTORE_FAILED: "+e.getMessage();}
  if(Files.exists(properties.configurationFile()))try{
   Selection stored=mapper.readValue(properties.configurationFile().toFile(),Selection.class);
   if(stored.engine().equals("ollama")){
    if(!stored.parameters().isEmpty())throw new AIProviderException("UNSUPPORTED_PARAMETERS");
    selected=new Selection(stored.configurationId(),"ollama",ollama.activeModel(),Map.of());
   }else{selected=stored;switchTo(stored.engine(),stored.model(),stored.parameters());selected=stored;persist(stored,false);}
  }catch(Exception e){status="ERROR";error="CONFIGURATION_RESTORE_FAILED: "+e.getMessage();}
 }
 public synchronized Selection selection(){Selection s=selected;if(s==null)return null;if(s.engine().equals("ollama")&&!s.model().equals(ollama.activeModel())){s=new Selection(UUID.randomUUID().toString(),"ollama",ollama.activeModel(),Map.of());selected=s;}return s;}
 public Map<String,Object> state(){
  Selection s=selection();String runtime=status;
  if(s.engine().equals("llamacpp")&&status.equals("READY")&&!process.alive())runtime="ERROR";
  var result=new LinkedHashMap<String,Object>();
  result.put("contractVersion","1.9.0");result.put("selection",s);result.put("status",runtime);result.put("error",error);result.put("metrics",lastMetrics);
  result.put("engines",List.of(Map.of("id","ollama","installationStatus","EXTERNAL","processOwnership","EXTERNAL"),Map.of("id","llamacpp","installationStatus",process.installed()?"INSTALLED":"NOT_INSTALLED","processOwnership","CORE_CHILD","running",process.alive())));
  result.put("resources",resources());result.put("parameterSchema",parameterSchema());result.put("revision",revision.get());result.put("operationId",lastOperationId);
  if(s.engine().equals("llamacpp"))result.put("runtimeLimits",Map.of("contextTokensPerSlot",contextPerRequest(s),"parallelSlots",s.parameters().getOrDefault("parallelSlots",1),"configuredTotalContextTokens",s.parameters().getOrDefault("contextSize",4096),"source",observedContextPerSlot>0?"BACKEND_PROPS":"CONFIGURED_FALLBACK"));
  return Collections.unmodifiableMap(result);
 }
 public List<Map<String,Object>> models(String engine){
  if(engine.equals("ollama"))return ollama.catalog().models().stream().map(m->Map.<String,Object>of("id",m.name(),"engine","ollama","sizeBytes",m.sizeBytes(),"declaredCapabilities",m.capabilities(),"verifiedCapabilities",List.of())).toList();
  if(!engine.equals("llamacpp"))throw new AIProviderException("UNKNOWN_ENGINE");
  if(!Files.isDirectory(properties.modelDirectory()))return List.of();
  try(var files=Files.walk(properties.modelDirectory(),4)){
   var rows=new ArrayList<Map<String,Object>>();
   for(Path file:files.filter(f->f.toString().endsWith(".gguf")&&!f.getFileName().toString().startsWith("mmproj")).sorted().toList()){
    String name=file.getFileName().toString();if(name.matches(".*-\\d{5}-of-\\d{5}\\.gguf")&&!name.matches(".*-00001-of-\\d{5}\\.gguf"))continue;
    String id=properties.modelDirectory().toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\','/');
    String problem="";try{validateModel(id);}catch(Exception e){problem=e.getMessage();}
    rows.add(Map.of("id",id,"engine","llamacpp","sizeBytes",Files.size(file),"status",problem.isEmpty()?"AVAILABLE":"INVALID","configurationRequired",!savedParameters.containsKey(modelKey("llamacpp",id)),"configurationStatus",savedParameters.containsKey(modelKey("llamacpp",id))?"SAVED":"REQUIRED","error",problem,"declaredCapabilities",List.of("TEXT","STREAMING"),"verifiedCapabilities",List.of()));
   }return rows;
  }catch(Exception e){throw new AIProviderException("MODEL_DISCOVERY_FAILED",e);}
 }
 Path safePath(String value,boolean gguf){
  try{Path root=properties.modelDirectory().toRealPath();Path path=root.resolve(value).normalize().toRealPath();
   if(!path.startsWith(root)||!Files.isRegularFile(path)||gguf&&!path.toString().endsWith(".gguf"))throw new AIProviderException("INVALID_MODEL_PATH");return path;
  }catch(java.io.IOException|java.nio.file.InvalidPathException e){throw new AIProviderException("MODEL_NOT_FOUND",e);}
 }
 Path validateModel(String id){
  Path file=safePath(id,true);verifyMagic(file);
  var match=java.util.regex.Pattern.compile("(.*)-(\\d{5})-of-(\\d{5})\\.gguf").matcher(file.getFileName().toString());
  if(match.matches()){
   int count=Integer.parseInt(match.group(3));if(!match.group(2).equals("00001")||count<1||count>1000)throw new AIProviderException("INVALID_MODEL_SHARDS");
   for(int i=1;i<=count;i++){Path shard=file.resolveSibling(String.format(Locale.ROOT,"%s-%05d-of-%05d.gguf",match.group(1),i,count));Path valid=safePath(properties.modelDirectory().toAbsolutePath().normalize().relativize(shard).toString(),true);verifyMagic(valid);}
  }return file;
 }
 private void verifyMagic(Path path){try(var in=Files.newInputStream(path)){if(!Arrays.equals(in.readNBytes(4),new byte[]{71,71,85,70})||Files.size(path)<24)throw new AIProviderException("INVALID_GGUF_HEADER");}catch(java.io.IOException e){throw new AIProviderException("INCOMPLETE_MODEL",e);}}
 private Map<String,Object> validate(String engine,Map<String,Object> input){
  Map<String,Object> p=new LinkedHashMap<>(input==null?Map.of():input);
  if(engine.equals("ollama")){if(!p.isEmpty())throw new AIProviderException("UNSUPPORTED_PARAMETERS: configure Ollama through existing profile settings");return p;}
  if(!engine.equals("llamacpp"))throw new AIProviderException("UNKNOWN_ENGINE");
  Set<String> allowed=Set.of("contextSize","maxOutputTokens","temperature","topP","topK","repeatPenalty","seed","reasoning","reasoningEffort","timeoutSeconds","gpuLayers","gpuBudgetMiB","cpuThreads","batchSize","microBatchSize","flashAttention","cacheTypeK","cacheTypeV","parallelSlots","chatTemplate","jinja","mmproj","memoryMapping","declaredCapabilities");
  if(!allowed.containsAll(p.keySet()))throw new AIProviderException("UNSUPPORTED_PARAMETERS");
  bounded(p,"contextSize",512,32768,4096);bounded(p,"maxOutputTokens",1,16384,1024);bounded(p,"timeoutSeconds",1,1800,120);
  bounded(p,"gpuLayers",0,200,0);bounded(p,"gpuBudgetMiB",0,16384,0);bounded(p,"cpuThreads",1,64,8);bounded(p,"batchSize",1,4096,512);bounded(p,"microBatchSize",1,512,128);bounded(p,"parallelSlots",1,16,1);
  if(((Number)p.get("maxOutputTokens")).intValue()>=((Number)p.get("contextSize")).intValue()/((Number)p.get("parallelSlots")).intValue())throw new AIProviderException("INVALID_OUTPUT_CONTEXT_BUDGET");
  if(((Number)p.get("microBatchSize")).intValue()>((Number)p.get("batchSize")).intValue())throw new AIProviderException("INVALID_BATCH_SIZE");
  for(String key:List.of("temperature","topP","repeatPenalty"))if(p.containsKey(key)){if(!(p.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()<0||n.doubleValue()>(key.equals("topP")?1:2))throw new AIProviderException("INVALID_PARAMETER: "+key);}
  if(p.containsKey("topK"))bounded(p,"topK",0,1000,40);
  if(p.containsKey("seed")&&(!(p.get("seed") instanceof Number n)||n.doubleValue()!=n.longValue()))throw new AIProviderException("INVALID_SEED");
  if(p.containsKey("reasoning")&&!Set.of("on","off","auto").contains(p.get("reasoning")))throw new AIProviderException("INVALID_REASONING");
  if(p.containsKey("reasoningEffort")&&!Set.of("low","medium","high").contains(p.get("reasoningEffort")))throw new AIProviderException("INVALID_REASONING_EFFORT");
  for(String k:List.of("jinja","memoryMapping"))if(p.containsKey(k)&&!(p.get(k) instanceof Boolean))throw new AIProviderException("INVALID_PARAMETER: "+k);
  for(String k:List.of("cacheTypeK","cacheTypeV"))if(p.containsKey(k)&&!Set.of("f16","q8_0","q4_0").contains(p.get(k)))throw new AIProviderException("INVALID_CACHE_TYPE");
  if(p.containsKey("flashAttention")&&!Set.of("on","off","auto").contains(p.get("flashAttention")))throw new AIProviderException("INVALID_FLASH_ATTENTION");
  for(String k:List.of("mmproj","chatTemplate"))if(p.containsKey(k)){if(!(p.get(k) instanceof String path))throw new AIProviderException("INVALID_PARAMETER: "+k);p.put(k,safePath(path,k.equals("mmproj")).toString());}
  if(p.containsKey("declaredCapabilities")){if(!(p.get("declaredCapabilities") instanceof List<?> list)||!Set.of("VISION","TOOLS","STRUCTURED_OUTPUT","REASONING").containsAll(list))throw new AIProviderException("INVALID_CAPABILITIES");}
  if(p.getOrDefault("declaredCapabilities",List.of()) instanceof List<?> caps&&caps.contains("VISION")&&!p.containsKey("mmproj"))throw new AIProviderException("VISION_PROJECTOR_REQUIRED");
  if(p.containsKey("reasoningEffort")&&!((List<?>)p.getOrDefault("declaredCapabilities",List.of())).contains("REASONING"))throw new AIProviderException("CAPABILITY_REQUIRED: REASONING");
  return Map.copyOf(p);
 }
 private void bounded(Map<String,Object> p,String key,int min,int max,int fallback){Object v=p.getOrDefault(key,fallback);if(!(v instanceof Number n)||!Double.isFinite(n.doubleValue())||n.doubleValue()!=n.intValue()||n.intValue()<min||n.intValue()>max)throw new AIProviderException("INVALID_PARAMETER: "+key);p.put(key,((Number)v).intValue());}
 private final Object switchLock=new Object();
 public Map<String,Object> switchTo(String engine,String model,Map<String,Object> input){
  synchronized(switchLock){return switchInternal(engine,model,input);}
 }
 private Map<String,Object> switchInternal(String engine,String model,Map<String,Object> input){
  if(engine==null||!Set.of("ollama","llamacpp").contains(engine))throw new AIProviderException("UNKNOWN_ENGINE");
  if(model==null||model.isBlank())throw new AIProviderException("MODEL_REQUIRED");
  Map<String,Object> supplied=input;
  if(engine.equals("llamacpp")){
   var saved=savedParameters.get(modelKey(engine,model));
   if(saved==null&&selected!=null&&selected.engine().equals(engine)&&selected.model().equals(model))saved=selected.parameters();
   if((input==null||input.isEmpty())&&(saved==null||saved.isEmpty()))throw new AIProviderException("MODEL_CONFIGURATION_REQUIRED: configure parameters before activation");
   supplied=(input==null||input.isEmpty())?saved:input;
  }
  Map<String,Object> p=validate(engine,supplied);if(engine.equals("llamacpp")){if(!properties.managedProcess())throw new AIProviderException("PROCESS_MANAGEMENT_DISABLED");if(!process.installed())throw new AIProviderException("NOT_INSTALLED");}Path modelPath=engine.equals("llamacpp")?validateModel(model):null;
  if(engine.equals("ollama")&&ollama.catalog().models().stream().noneMatch(m->m.name().equals(model)))throw new AIProviderException("MODEL_NOT_INSTALLED");
  String operation=UUID.randomUUID().toString();String owner=CurrentUserContext.currentUserId().orElse(null);
  try(var exclusive=gate.exclusive()){
   Selection previous=selection();status="SWITCHING";publish(owner,operation);
   try{
    if(previous.engine().equals("llamacpp"))process.stop();
    if(engine.equals("llamacpp")){
     if(previous.engine().equals("ollama"))unloadOllama();checkResources(p);status="LOADING_MODEL";publish(owner,operation);
     process.start(modelPath,model,p);process.awaitReady(Duration.ofSeconds(180));verifyModels(model);observeLimits(p);
    }else {observedContextPerSlot=0;unloadOllama();warmOllama(model);}
    Selection next=new Selection(UUID.randomUUID().toString(),engine,model,p);
    persist(next);
    if(engine.equals("ollama")){var result=ollama.switchTo(model);if(!result.success())throw new AIProviderException("OLLAMA_SELECTION_FAILED: "+result.error());}
    saveProfile(next);selected=next;gate.activate(engine);status="READY";error="";publish(owner,operation);
   }catch(Exception failure){
    status="RECOVERING";error=failure.getMessage();publish(owner,operation);
    try{process.stop();if(previous.engine().equals("llamacpp")){process.start(validateModel(previous.model()),previous.model(),previous.parameters());process.awaitReady(Duration.ofSeconds(180));observeLimits(previous.parameters());}else{observedContextPerSlot=0;warmOllama(previous.model());ollama.switchTo(previous.model());}persist(previous,false);selected=previous;gate.activate(previous.engine());status="READY";}
    catch(Exception recovery){status="ERROR";error+="; ROLLBACK_FAILED: "+recovery.getMessage();gate.activate("unavailable");}
    publish(owner,operation);throw new AIProviderException("ENGINE_SWITCH_FAILED: "+error,failure);
   }
  }
  return Map.of("operationId",operation,"selection",selection(),"status",status);
 }
 private void persist(Selection next)throws Exception{persist(next,true);}
 private void persist(Selection next,boolean backup)throws Exception{
  Path path=properties.configurationFile().toAbsolutePath();Files.createDirectories(path.getParent());
  if(backup&&Files.exists(path))Files.copy(path,path.resolveSibling(path.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
  Path temporary=Files.createTempFile(path.getParent(),"inference-",".tmp");
  try{Files.write(temporary,mapper.writeValueAsBytes(next));try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}finally{Files.deleteIfExists(temporary);}
 }
 private JsonNode ollama(String endpoint,Object body){return json(ollamaProperties.baseUrl()+endpoint,body);}
 private JsonNode json(String url,Object body){try{var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(180));if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new AIProviderException("BACKEND_HTTP_"+response.statusCode());return mapper.readTree(response.body());}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new AIProviderException("ENGINE_CONTROL_FAILED",e);}}
 private void unloadOllama(){for(JsonNode m:ollama("/api/ps",null).path("models"))ollama("/api/generate",Map.of("model",m.path("name").asText(),"keep_alive",0,"stream",false));long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(!ollama("/api/ps",null).path("models").isEmpty()){if(System.nanoTime()>end)throw new AIProviderException("OLLAMA_UNLOAD_TIMEOUT");try{Thread.sleep(200);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AIProviderException("CANCELLED",e);}}}
 private void warmOllama(String model){ollama("/api/generate",Map.of("model",model,"keep_alive",ollamaProperties.keepAlive(),"stream",false));}
 private void verifyModels(String model){boolean found=false;for(JsonNode row:json(properties.baseUrl()+"/v1/models",null).path("data"))if(row.path("id").asText().equals(model))found=true;if(!found)throw new AIProviderException("MODEL_READINESS_MISMATCH");}
 public Map<String,Object> process(String action){if(action==null||!Set.of("start","restart","stop").contains(action))throw new AIProviderException("INVALID_PROCESS_ACTION");var s=selection();if(!s.engine().equals("llamacpp"))throw new AIProviderException("ENGINE_INACTIVE");if(action.equals("stop")){try(var lease=gate.exclusive()){process.stop();status="STOPPED";gate.activate("unavailable");}return state();}return switchTo(s.engine(),s.model(),s.parameters());}
 public void validateCapabilities(List<ModelMessage> messages,List<NativeToolDefinition> tools,Map<String,Object> schema){var caps=(List<?>)selection().parameters().getOrDefault("declaredCapabilities",List.of());if(!tools.isEmpty()&&!caps.contains("TOOLS"))throw new AIProviderException("CAPABILITY_REQUIRED: TOOLS");if(!schema.isEmpty()&&!caps.contains("STRUCTURED_OUTPUT"))throw new AIProviderException("CAPABILITY_REQUIRED: STRUCTURED_OUTPUT");if(messages.stream().anyMatch(m->!m.images().isEmpty())&&!caps.contains("VISION"))throw new AIProviderException("CAPABILITY_REQUIRED: VISION");}
 public void metrics(long start,long first,ModelUsage usage){long end=System.nanoTime();Map<String,Object> m=new LinkedHashMap<>();m.put("totalMs",TimeUnit.NANOSECONDS.toMillis(end-start));m.put("timeToFirstTokenMs",first==0?null:TimeUnit.NANOSECONDS.toMillis(first-start));m.put("usage",usage);m.put("generationTokensPerSecond",first==0||usage.completionTokens()==0?null:usage.completionTokens()*1e9/Math.max(1,end-first));lastMetrics=Collections.unmodifiableMap(m);}
 private void publish(String owner,String operation){lastOperationId=operation;long currentRevision=revision.incrementAndGet();if(owner!=null){var sink=events.getIfAvailable();if(sink!=null)sink.record(owner,"inference.operation.updated",null,Map.of("operationId",operation,"revision",currentRevision,"status",status,"selection",selection(),"error",error));}}
 public Map<String,Object> parameterSchema(){
  Map<String,Object> schema=new LinkedHashMap<>();
  schema.put("contextSize",Map.of("type","integer","minimum",512,"maximum",32768,"default",4096,"requiresReload",true));
  schema.put("maxOutputTokens",Map.of("type","integer","minimum",1,"maximum",16384,"default",1024));
  schema.put("parallelSlots",Map.of("type","integer","minimum",1,"maximum",16,"default",1,"requiresReload",true));
  schema.put("gpuLayers",Map.of("type","integer","minimum",0,"maximum",200,"default",0));
  schema.put("gpuBudgetMiB",Map.of("type","integer","minimum",0,"maximum",16384,"default",0));
  schema.put("timeoutSeconds",Map.of("type","integer","minimum",1,"maximum",1800,"default",120));
  schema.put("cpuThreads",Map.of("type","integer","minimum",1,"maximum",64,"default",8));
  schema.put("batchSize",Map.of("type","integer","minimum",1,"maximum",4096,"default",512));
  schema.put("microBatchSize",Map.of("type","integer","minimum",1,"maximum",512,"default",128));
  schema.put("temperature",Map.of("type","number","minimum",0,"maximum",2));schema.put("topP",Map.of("type","number","minimum",0,"maximum",1));schema.put("repeatPenalty",Map.of("type","number","minimum",0,"maximum",2));schema.put("topK",Map.of("type","integer","minimum",0,"maximum",1000));schema.put("seed",Map.of("type","integer"));
  schema.put("reasoning",Map.of("enum",List.of("on","off","auto"),"requiresReload",true));
  schema.put("reasoningEffort",Map.of("enum",List.of("low","medium","high"),"requiresCapability","REASONING"));schema.put("flashAttention",Map.of("enum",List.of("on","off","auto")));
  for(String key:List.of("cacheTypeK","cacheTypeV"))schema.put(key,Map.of("enum",List.of("f16","q8_0","q4_0")));
  for(String key:List.of("jinja","memoryMapping"))schema.put(key,Map.of("type","boolean"));
  for(String key:List.of("mmproj","chatTemplate"))schema.put(key,Map.of("type","string","pathScope","MODEL_DIRECTORY"));
  schema.put("declaredCapabilities",Map.of("type","array","items",Map.of("enum",List.of("VISION","TOOLS","STRUCTURED_OUTPUT","REASONING"))));
  return Map.of("llamacpp",schema,"ollama",Map.of("parameters",List.of(),"settingsSource","EXISTING_INFERENCE_PROFILES"));
 }
 public String residency(){var s=selection();if(s.engine().equals("llamacpp"))return process.alive()&&process.healthy()?"LOADED":"NOT_LOADED";try{for(var row:ollama("/api/ps",null).path("models"))if(row.path("name").asText().equals(s.model()))return "LOADED";return "NOT_LOADED";}catch(Exception e){return "UNKNOWN";}}
 public List<String> logs(){return process.logs();}
 private void checkResources(Map<String,Object> p){var memory=resources().get("ramAvailableBytes");if(memory instanceof Long bytes&&bytes<8L*1024*1024*1024)throw new AIProviderException("INSUFFICIENT_RAM: 8 GiB reserve required");if(((Number)p.get("gpuLayers")).intValue()==0)return;var sample=resources();Object free=sample.get("gpuFreeMiB");int budget=((Number)p.get("gpuBudgetMiB")).intValue();if(!(free instanceof Long n)||budget<1||n<budget+2048)throw new AIProviderException("INSUFFICIENT_OR_UNVERIFIED_VRAM: gpuFreeMiB="+free+", gpuBudgetMiB="+budget+", reserveMiB=2048, requiredFreeMiB="+(budget+2048)+"; action=verify nvidia-smi availability and approve a model/KV/projector VRAM budget before activation");}
 public Map<String,Object> resources(){Map<String,Object> result=new LinkedHashMap<>();result.put("observedAt",Instant.now().toString());result.put("gpuFreeMiB",null);result.put("ramAvailableBytes",null);result.put("scope","HOST_OBSERVATION");
  try{Path mem=Path.of("/proc/meminfo");if(Files.exists(mem))for(String row:Files.readAllLines(mem))if(row.startsWith("MemAvailable:"))result.put("ramAvailableBytes",Long.parseLong(row.replaceAll("[^0-9]",""))*1024);}catch(Exception ignored){}
  Process sample=null;try{sample=new ProcessBuilder("nvidia-smi","--query-gpu=memory.free","--format=csv,noheader,nounits").redirectErrorStream(true).start();if(sample.waitFor(2,TimeUnit.SECONDS)&&sample.exitValue()==0){String value=new String(sample.getInputStream().readAllBytes()).trim();if(value.matches("[0-9]+"))result.put("gpuFreeMiB",Long.parseLong(value));}}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();}finally{if(sample!=null&&sample.isAlive())sample.destroyForcibly();}result.put("status",result.get("gpuFreeMiB")==null?"UNAVAILABLE":"AVAILABLE");return result;
 }
}
