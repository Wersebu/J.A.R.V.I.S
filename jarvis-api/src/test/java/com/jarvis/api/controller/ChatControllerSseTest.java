package com.jarvis.api.controller;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.util.function.Consumer;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
class ChatControllerSseTest {
 @Test void failureAfterFirstEventIsAnSseFrameAndCompletesNormally()throws Exception{
  var service=mock(ChatService.class);var store=new PendingChatStreamStore();var controller=new ChatController(service,store);
  doAnswer(i->{Consumer<CognitiveEvent> sink=i.getArgument(1);sink.accept(new CognitiveEvent("request","conversation",java.time.Instant.now(),CognitiveEventType.ANSWER_TOKEN,"TOKEN","hello",null,"model",null,Map.of("text","hello")));throw new IllegalStateException("private backend details");}).when(service).stream(any(),any());
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();String token=store.store(new ChatRequest("conversation","hello"));
  var pending=mvc.perform(get("/api/v1/chat/stream").param("token",token)).andReturn();pending.getAsyncResult(5000);
  var result=mvc.perform(asyncDispatch(pending)).andReturn();
  assertThat(result.getResolvedException()).isNull();assertThat(result.getResponse().getContentType()).startsWith("text/event-stream");
  assertThat(result.getResponse().getContentAsString()).contains("event:ANSWER_TOKEN","event:ERROR","INFERENCE_FAILED","\"requestId\":\"request\"").doesNotContain("private backend details");
 }
}
