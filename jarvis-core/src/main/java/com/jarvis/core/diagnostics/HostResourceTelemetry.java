package com.jarvis.core.diagnostics;

import com.jarvis.common.companion.ResourceTelemetry;
import org.springframework.stereotype.Service;
import jakarta.annotation.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Read-only host measurements, never JVM heap as a proxy for GPU/RAM availability. */
@Service
public class HostResourceTelemetry implements ResourceTelemetry {
    private final ScheduledExecutorService sampler=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"host-resource-sampler");t.setDaemon(true);return t;});
    private volatile Map<String,Object> measured=Map.of();
    private volatile long observed;
    @PostConstruct public void start(){sampler.scheduleWithFixedDelay(this::sample,0,5,TimeUnit.SECONDS);}
    private void sample(){
        var values=new LinkedHashMap<String,Object>();
        try{
            for(String line:Files.readAllLines(Path.of("/proc/meminfo")))if(line.startsWith("MemAvailable:"))
                values.put("availableHostRamBytes",Long.parseLong(line.split("\\s+")[1])*1024);
        }catch(Exception ignored){}
        Process process=null;
        try{
            process=new ProcessBuilder("nvidia-smi","--query-gpu=uuid,memory.total,memory.used,memory.free","--format=csv,noheader,nounits").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(process.waitFor(2,TimeUnit.SECONDS)&&process.exitValue()==0){
                String output=new String(process.getInputStream().readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8);
                var gpus=new ArrayList<Map<String,Object>>();
                for(String line:output.split("\\R")){String[] fields=line.split(",\\s*");if(fields.length==4)gpus.add(Map.of("uuid",fields[0],"totalBytes",Long.parseLong(fields[1].strip())*1048576,"usedBytes",Long.parseLong(fields[2].strip())*1048576,"freeBytes",Long.parseLong(fields[3].strip())*1048576));}
                if(!gpus.isEmpty())values.put("gpus",gpus);
            }
        }catch(Exception ignored){}finally{if(process!=null&&process.isAlive())process.destroyForcibly();}
        measured=Map.copyOf(values);observed=System.currentTimeMillis();
    }
    public Map<String,Object> snapshot(){
        long at=observed;var result=new LinkedHashMap<String,Object>();
        result.put("status",at==0||measured.isEmpty()?"UNAVAILABLE":System.currentTimeMillis()-at>15000?"STALE":"AVAILABLE");
        if(at>0){result.put("observedAt",Instant.ofEpochMilli(at));result.put("source","HOST_OS_NVIDIA_SMI");if(System.currentTimeMillis()-at<=15000)result.putAll(measured);}
        return result;
    }
    @PreDestroy public void close(){sampler.shutdownNow();}
}
