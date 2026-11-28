package com.jarvis.inference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.CompanionEventSink;
import com.jarvis.ollama.OllamaProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InferenceModelManagerTest {
 @TempDir Path root;InferenceModelManager models;InferenceEngineManager engines;
 @BeforeEach void setup(){CurrentUserContext.set("admin");var p=new InferenceProperties(root.resolve("config"),root,root.resolve("server"),8081,true);engines=mock(InferenceEngineManager.class);ObjectProvider<CompanionEventSink> events=mock(ObjectProvider.class);models=new InferenceModelManager(p,engines,new OllamaProperties(null,null,null,true,null),new ObjectMapper(),events,new EngineAccessGate());}
 @AfterEach void close(){models.close();CurrentUserContext.clear();}
 @Test void hfMustUseCommitBasenameAndDigest(){assertThatThrownBy(()->models.download(new InferenceModelOperations.Download("llamacpp","org/repo","main","a.gguf","0".repeat(64)))).hasMessageContaining("INVALID_HF");assertThatThrownBy(()->models.download(new InferenceModelOperations.Download("llamacpp","org/repo","a".repeat(40),"../a.gguf","0".repeat(64)))).hasMessageContaining("INVALID_HF");}
 @Test void importChecksHashWithoutCopying()throws Exception{Path file=Files.write(root.resolve("a.gguf"),new byte[24]);when(engines.validateModel("a.gguf")).thenReturn(file);String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));assertThat(models.importModel("a.gguf",digest)).containsEntry("copied",false);assertThatThrownBy(()->models.importModel("a.gguf","f".repeat(64))).hasMessageContaining("MODEL_IMPORT_FAILED");}
 @Test void activeModelCannotBeDeleted(){when(engines.selection()).thenReturn(new InferenceControl.Selection("id","llamacpp","a.gguf",Map.of()));assertThatThrownBy(()->models.delete("llamacpp","a.gguf")).hasMessageContaining("ACTIVE_MODEL");}
 @Test void operationsRequireAuthenticatedOwner(){CurrentUserContext.clear();assertThatThrownBy(()->models.operations()).hasMessageContaining("Authenticated user");}
 @Test void cannotCancelUnknownOrOtherOwnersOperation(){assertThatThrownBy(()->models.cancel("unknown")).hasMessageContaining("OPERATION_NOT_FOUND");}
}
