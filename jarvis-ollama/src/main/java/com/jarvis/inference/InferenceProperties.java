package com.jarvis.inference;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;
@ConfigurationProperties(prefix="jarvis.inference.engines")
public record InferenceProperties(Path configurationFile,Path modelDirectory,Path executable,int port,boolean managedProcess) {
 public InferenceProperties {
  if(configurationFile==null)configurationFile=Path.of("config/inference-engine.json");
  if(modelDirectory==null)modelDirectory=Path.of("/opt/jarvis/models/llamacpp");
  if(executable==null)executable=Path.of("/opt/jarvis/engines/llamacpp/current/bin/llama-server");
  if(port==0)port=8081;
  if(port<1024||port>65535)throw new IllegalArgumentException("llama-server port must be 1024..65535");
 }
 public String baseUrl(){return "http://127.0.0.1:"+port;}
}
