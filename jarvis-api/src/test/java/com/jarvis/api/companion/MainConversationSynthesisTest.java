package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.common.ai.*;
import com.jarvis.common.companion.*;
import com.jarvis.common.prompt.*;
import com.jarvis.memory.ConversationMemoryService;
import com.jarvis.memory.conversation.ConversationRepository;
import com.jarvis.brain.BrainRouter;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class MainConversationSynthesisTest {
 @Test void workerEvidenceReturnsToTheOriginalMainIdentityAndConversation()throws Exception{
  var mapper=new ObjectMapper();var store=new InMemoryCompanionStore();var originalBrain=new Brain(BrainType.REASONING,"fake","original-model","main","",0,ReasoningLevel.MEDIUM,"original-config");
  store.put(new CompanionDocumentStore.Document("alice","task-input","root",mapper.writeValueAsString(Map.of("request",Map.of("parentRequestId","original-request"))),Instant.now()));
  store.put(new CompanionDocumentStore.Document("alice","main-conversation-context","conversation/original-request",mapper.writeValueAsString(Map.of("prompt","MARKDOWN_JARVIS_PERSONALITY USER_SETTINGS FOLDER_SETTINGS KNOWLEDGE_MEMORY HISTORY: oryginalne polskie polecenie","brain",originalBrain)),Instant.now()));
  var memory=mock(ConversationMemoryService.class);var delivery=new TaskResultDelivery(memory,mock(ConversationRepository.class));var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("fake");when(provider.supportsStreamingChat()).thenReturn(true);
  String answer="Oto potwierdzone wyniki. Gmail jest chwilowo niedostępny; pozostałe dane zostały sprawdzone.";
  String json=mapper.writeValueAsString(Map.of("action","DIRECT","answer",answer));
  when(provider.streamChat(any(),anyList(),anyList(),anyMap(),any(),any())).thenAnswer(i->{java.util.function.Consumer<ModelStreamEvent> sink=i.getArgument(5);sink.accept(new ModelStreamEvent(ModelStreamEvent.Type.REASONING,"PRIVATE_THINKING","",""));sink.accept(ModelStreamEvent.content(json));return new ModelResponse(json,"PRIVATE_THINKING",List.of(),"stop",new ModelUsage(20,10,30));});
  var router=mock(BrainRouter.class);delivery.configureSynthesis(List.of(provider),router);ObjectProvider<CompanionDocumentStore> documents=mock(ObjectProvider.class);when(documents.getIfAvailable()).thenReturn(store);delivery.conversationContext(mock(ObjectProvider.class),mock(ObjectProvider.class),documents);
  var streamed=new StringBuilder();String[] actual={null};AgentScope.run(new AgentScope.Scope("root","root",0,Set.of(),true),()->actual[0]=delivery.synthesize("alice","conversation","{\"outcomes\":[{\"error\":\"TEMPORARILY_UNAVAILABLE\",\"result\":\"UNTRUSTED_WORKER_PROSE\"}]}",streamed::append));
  assertThat(actual[0]).isEqualTo(answer);assertThat(streamed.toString()).isEqualTo(answer).doesNotContain("PRIVATE_THINKING","UNTRUSTED_WORKER_PROSE");
  var messages=org.mockito.ArgumentCaptor.forClass(List.class);verify(provider,times(1)).streamChat(eq(originalBrain),messages.capture(),eq(List.of()),anyMap(),eq(AIJobType.BACKGROUND),any());assertThat(((ModelMessage)messages.getValue().getFirst()).content()).contains("MARKDOWN_JARVIS_PERSONALITY","USER_SETTINGS","KNOWLEDGE_MEMORY","HISTORY");verifyNoInteractions(router);
 }
}
