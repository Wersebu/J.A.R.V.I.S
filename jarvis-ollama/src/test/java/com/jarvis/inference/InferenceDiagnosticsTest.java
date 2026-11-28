package com.jarvis.inference;
import com.jarvis.common.ai.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InferenceDiagnosticsTest {
 @Test void benchmarkLabelsObservedResidencyInsteadOfInventingWarmStart(){
  for(String residency:List.of("LOADED","NOT_LOADED","UNKNOWN")){
   var manager=mock(InferenceEngineManager.class);when(manager.selection()).thenReturn(new InferenceControl.Selection("id","ollama","existing",Map.of()));when(manager.resources()).thenReturn(Map.of());when(manager.residency()).thenReturn(residency);
   var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("ollama");when(provider.streamChat(any(),anyList(),anyList(),anyMap(),any(),any())).thenAnswer(i->{java.util.function.Consumer<ModelStreamEvent> sink=i.getArgument(5);sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.FIRST_TOKEN,"","",""));return new ModelResponse("answer","",List.of(),"stop",new ModelUsage(4,2,6));});
   ObjectProvider<AIProvider> providers=mock(ObjectProvider.class);when(providers.orderedStream()).thenReturn(Stream.of(provider));
   var diagnostics=new InferenceDiagnostics(manager,new InferenceProperties(Path.of("c"),Path.of("m"),Path.of("x"),8081,true),providers,new EngineAccessGate());
   var result=diagnostics.benchmark();String expected=residency.equals("LOADED")?"WARM_RESIDENT":residency.equals("NOT_LOADED")?"COLD_MODEL_LOAD":"RESIDENCY_UNVERIFIED";assertThat(result.get("mode")).isEqualTo(expected);assertThat(result.get("promptProcessingTokensPerSecond")).isNull();assertThat(result.get("toolCallingTest")).isEqualTo("NOT_RUN");diagnostics.close();
  }
 }
}
