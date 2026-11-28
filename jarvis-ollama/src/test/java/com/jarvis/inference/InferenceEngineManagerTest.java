package com.jarvis.inference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.model.*;
import com.jarvis.common.companion.CompanionEventSink;
import com.jarvis.common.ai.*;
import com.jarvis.ollama.OllamaProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InferenceEngineManagerTest {
 @TempDir Path root;
 InferenceEngineManager manager;LlamaServerProcess process;ActiveModelService ollama;
 @BeforeEach void setup(){var props=new InferenceProperties(root.resolve("config.json"),root,root.resolve("missing-server"),8081,true);process=mock(LlamaServerProcess.class);when(process.installed()).thenReturn(true);ollama=mock(ActiveModelService.class);when(ollama.activeModel()).thenReturn("existing-model");ObjectProvider<CompanionEventSink> events=mock(ObjectProvider.class);manager=new InferenceEngineManager(props,new ObjectMapper(),ollama,new OllamaProperties("http://127.0.0.1:1","existing-model",null,true,null),new EngineAccessGate(),process,events);manager.restore();}
 Path gguf(String name)throws Exception{Path path=root.resolve(name);byte[] bytes=new byte[24];bytes[0]=71;bytes[1]=71;bytes[2]=85;bytes[3]=70;return Files.write(path,bytes);}
 @Test void migrationPreservesExistingOllamaModel(){assertThat(manager.selection().engine()).isEqualTo("ollama");assertThat(manager.selection().model()).isEqualTo("existing-model");assertThat(Files.exists(root.resolve("config.json"))).isFalse();}
 @Test void rejectsTraversalAndIncompleteFiles()throws Exception{Files.writeString(root.resolve("bad.gguf"),"GG");assertThatThrownBy(()->manager.validateModel("../escape.gguf")).hasMessageContaining("MODEL_NOT_FOUND");assertThatThrownBy(()->manager.validateModel("bad.gguf")).hasMessageContaining("GGUF");}
 @Test void shardSetMustBeComplete()throws Exception{gguf("model-00001-of-00002.gguf");assertThatThrownBy(()->manager.validateModel("model-00001-of-00002.gguf")).hasMessageContaining("MODEL_NOT_FOUND");gguf("model-00002-of-00002.gguf");assertThat(manager.validateModel("model-00001-of-00002.gguf")).isNotNull();assertThat(manager.models("llamacpp")).hasSize(1);}
 @Test void arbitraryOptionsAndSlotsRejected()throws Exception{gguf("a.gguf");assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of("command","rm"))).hasMessageContaining("UNSUPPORTED_PARAMETERS");assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of("parallelSlots",2))).hasMessageContaining("INVALID_PARAMETER");verify(process,never()).start(any(),any(),any());}
 @Test void capabilityValidationIsConservative(){assertThatThrownBy(()->manager.validateCapabilities(List.of(),List.of(new NativeToolDefinition("x","",Map.of())),Map.of())).hasMessageContaining("TOOLS");}
 @Test void unavailableOllamaPreventsLoadingSecondEngine()throws Exception{gguf("a.gguf");assertThatThrownBy(()->manager.switchTo("llamacpp","a.gguf",Map.of())).hasMessageContaining("ENGINE_SWITCH_FAILED");verify(process,never()).start(any(),any(),any());assertThat(manager.state().get("status")).isEqualTo("ERROR");}
 @Test void corruptedConfigurationDoesNotPretendRestoreSucceeded()throws Exception{Files.writeString(root.resolve("config.json"),"bad json");manager.restore();assertThat(manager.state().get("error").toString()).contains("CONFIGURATION_RESTORE_FAILED");assertThat(manager.selection().engine()).isEqualTo("ollama");}
}
