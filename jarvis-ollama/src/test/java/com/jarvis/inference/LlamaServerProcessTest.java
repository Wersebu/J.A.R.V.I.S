package com.jarvis.inference;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class LlamaServerProcessTest {
 private LlamaServerProcess process(){return new LlamaServerProcess(new InferenceProperties(Path.of("c"),Path.of("m"),Path.of("server"),8081,true));}
 @Test void cpuModeAlsoKeepsMultimodalProjectorOffGpu(){var args=process().command(Path.of("model.gguf"),"alias",Map.of("gpuLayers",0,"mmproj","projector.gguf"));assertThat(args).containsSubsequence("--host","127.0.0.1").containsSubsequence("--device","none").contains("--no-mmproj-offload","--jinja").containsSubsequence("--reasoning-format","auto");}
 @Test void explicitJinjaFalseIsNotSilentlyIgnored(){assertThat(process().command(Path.of("model.gguf"),"alias",Map.of("jinja",false))).contains("--no-jinja").doesNotContain("--jinja");}
 @Test void gpuBudgetDoesNotBecomeAnUnsupportedBackendFlag(){var args=process().command(Path.of("model with spaces.gguf"),"alias",Map.of("gpuLayers",8,"gpuBudgetMiB",2048));assertThat(args).containsSubsequence("--n-gpu-layers","8").doesNotContain("--device","gpuBudgetMiB");assertThat(args).contains("model with spaces.gguf");}
 @Test void managementDisabledFailsBeforeLaunch(){var p=new LlamaServerProcess(new InferenceProperties(Path.of("c"),Path.of("m"),Path.of("missing"),8081,false));assertThatThrownBy(()->p.start(Path.of("model.gguf"),"alias",Map.of())).hasMessageContaining("PROCESS_MANAGEMENT_DISABLED");assertThat(p.alive()).isFalse();}
 @Test void missingExecutableIsReportedWithoutLaunching(){var p=process();assertThatThrownBy(()->p.start(Path.of("model.gguf"),"alias",Map.of())).hasMessageContaining("NOT_INSTALLED");}
}
