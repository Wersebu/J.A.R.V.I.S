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
 @Test void failedSourceVenueIsRejectedBeforeAnyVisibleChunk(){
  var memory=mock(ConversationMemoryService.class);when(memory.getMessages(anyString())).thenReturn(List.of());var delivery=new TaskResultDelivery(memory,mock(ConversationRepository.class));var router=mock(BrainRouter.class);var brain=new Brain(BrainType.FAST,"fake","m","");when(router.select(any(com.jarvis.common.dto.ChatRequest.class))).thenReturn(brain);
  var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("fake");when(provider.supportsStreamingChat()).thenReturn(true);String raw="{\"action\":\"DIRECT\",\"answer\":\"CSS na NASDAQ: brak danych\"}";
  when(provider.streamChat(any(),anyList(),anyList(),anyMap(),any(),any())).thenAnswer(i->{java.util.function.Consumer<ModelStreamEvent> sink=i.getArgument(5);sink.accept(ModelStreamEvent.content(raw));return new ModelResponse(raw,"",List.of(),"stop",null);});delivery.configureSynthesis(List.of(provider),router);var visible=new StringBuilder();
  assertThatThrownBy(()->delivery.synthesize("alice","conversation","{\"outcomes\":[{\"state\":\"FAILED\",\"name\":\"CSS NASDAQ\"}]}",visible::append)).hasMessageContaining("venue");assertThat(visible).isEmpty();
 }
 @Test void toolsOnlyLlamaAggregatesWithoutStructuredOutputAndPublishesOnce(){
  var memory=mock(ConversationMemoryService.class);when(memory.getMessages(anyString())).thenReturn(List.of());var delivery=new TaskResultDelivery(memory,mock(ConversationRepository.class));var router=mock(BrainRouter.class);var brain=new Brain(BrainType.FAST,"llamacpp","m","");when(router.select(any(com.jarvis.common.dto.ChatRequest.class))).thenReturn(brain);
  var control=mock(InferenceControl.class);when(control.selection()).thenReturn(new InferenceControl.Selection("c","llamacpp","m",Map.of("declaredCapabilities",List.of("TOOLS"))));org.springframework.test.util.ReflectionTestUtils.setField(delivery,"inference",control);
  var provider=mock(AIProvider.class);when(provider.provider()).thenReturn("llamacpp");when(provider.toolChat(any(),anyList(),anyList(),any())).thenReturn(new ModelResponse("","",List.of(new ModelToolCall("response","conversation__answer",Map.of("answer","Confirmed result"))),"tool_calls",null));delivery.configureSynthesis(List.of(provider),router);var chunks=new ArrayList<String>();
  assertThat(delivery.synthesize("alice","conversation","{\"outcomes\":[{\"state\":\"COMPLETED\",\"result\":\"Confirmed result\"}]}",chunks::add)).isEqualTo("Confirmed result");assertThat(chunks).containsExactly("Confirmed result");verify(control,times(1)).selection();verify(provider,never()).streamChat(any(),anyList(),anyList(),anyMap(),any(),any());
 }
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
