package com.jarvis.memory.autonomous;
import com.jarvis.common.ai.ModelGateway;
import com.jarvis.common.auth.CurrentUserContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
/** Opt-in idle maintenance, external memory only. No model weights or automatic conflict resolutions. */
@Service
public class AutonomousDreamingEngine {
    private final AutonomousMemoryPipeline pipeline;private final AutonomousMemoryService memory;private final ObjectProvider<ModelGateway> gateways;private final boolean enabled;private final long idleMillis;
    public AutonomousDreamingEngine(AutonomousMemoryPipeline pipeline,AutonomousMemoryService memory,ObjectProvider<ModelGateway> gateways,@Value("${jarvis.memory.dreaming.enabled:false}") boolean enabled,@Value("${jarvis.memory.dreaming.idle-millis:300000}") long idleMillis){this.pipeline=pipeline;this.memory=memory;this.gateways=gateways;this.enabled=enabled;this.idleMillis=Math.max(1000,idleMillis);}
    @Scheduled(fixedDelayString="${jarvis.memory.dreaming.check-millis:60000}") public void tick(){if(!enabled||!pipeline.enabled()||!pipeline.idle(idleMillis))return;for(var gateway:gateways)if(((Number)gateway.snapshot().getOrDefault("activeInferenceRequests",0)).intValue()>0)return;pipeline.maintenance(()->{for(String owner:memory.owners())CurrentUserContext.runAs(owner,()->memory.consolidate(owner));});}
}
