package com.jarvis.knowledge.vault;
import com.jarvis.knowledge.*;
import com.jarvis.knowledge.workspace.*;
import com.jarvis.knowledge.retrieval.KnowledgeRetriever;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.event.CognitiveEventBus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class KnowledgeDraftOwnerIsolationTest {
 @TempDir Path root;
 @Test void anotherAccountCannotListApproveOrRejectPendingDraft(){
  var service=new DefaultKnowledgeWorkspaceService(new KnowledgeProperties(root.toString(),false,500,300),new KnowledgeWorkspaceProperties(KnowledgeWriteMode.AUTO_DRAFT,null,null),mock(KnowledgeService.class),mock(KnowledgeIndex.class),mock(KnowledgeRetriever.class),mock(CognitiveEventBus.class),null);
  String[] id={null};CurrentUserContext.runAs("alice",()->{var draft=service.createDocument(null,"isolated.md","Owner data");id[0]=(String)draft.data().get("draftId");assertThat(service.drafts()).hasSize(1);});
  CurrentUserContext.runAs("bob",()->{assertThat(service.drafts()).isEmpty();assertThat(service.approveDraft(id[0]).applied()).isFalse();assertThat(service.rejectDraft(id[0]).message()).contains("not found");});
  assertThat(root.resolve("isolated.md")).doesNotExist();
  CurrentUserContext.runAs("alice",()->{assertThat(service.drafts()).hasSize(1);assertThat(service.approveDraft(id[0]).applied()).isTrue();assertThat(service.drafts()).isEmpty();});
  assertThat(root.resolve("isolated.md")).exists();
 }
}
