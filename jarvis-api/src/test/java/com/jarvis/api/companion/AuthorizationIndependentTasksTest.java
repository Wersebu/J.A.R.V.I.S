package com.jarvis.api.companion;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.companion.*;
import com.jarvis.common.dto.ChatResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.taskplan.DynamicPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AuthorizationIndependentTasksTest {
 @Test void oauthWaitDoesNotBlockIndependentStepsAndOnlyMainSynthesisIsDelivered()throws Exception{
  var waiting=new CountDownLatch(1);var release=new CountDownLatch(1);var independent=new CountDownLatch(2);var chat=mock(ChatService.class);String original="Sprawdź pięć maili, kurs dolara i pogodę na jutro, przygotuj jedną odpowiedź";
  when(chat.chat(any())).thenAnswer(i->{assertThat(TaskExecutionContext.languageReference()).isEqualTo(original);String goal=((com.jarvis.common.dto.ChatRequest)i.getArgument(0)).message();if(goal.equals("mail")){TaskExecutionContext.report("WAITING_FOR_AUTHORIZATION","Google");waiting.countDown();release.await(4,TimeUnit.SECONDS);TaskExecutionContext.report("RUNNING",null);throw new IncompleteTaskException("AUTHORIZATION_CANCELLED",null);}independent.countDown();return new ChatResponse("PRIVATE_WORKER_"+goal);});
  var store=new InMemoryCompanionStore();var mapper=new ObjectMapper().findAndRegisterModules();
  try(var tasks=new CompanionTasks(chat,1)){
   tasks.configureHistory(store,mapper,new DynamicPlanService(store,mapper),30);var delivery=mock(TaskResultDelivery.class);when(delivery.deliver(anyString(),anyString(),anyString(),anyString(),any())).thenReturn(TaskResultDelivery.Status.DELIVERED);when(delivery.synthesize(anyString(),anyString(),anyString(),any())).thenReturn("MAIN_JARVIS: kurs i pogoda sprawdzone, Gmail nie został odczytany.");ReflectionTestUtils.setField(tasks,"resultDelivery",delivery);
   var agents=List.of(new AutonomousOrchestration.Agent("mail","Gmail","mail",List.of(),List.of()),new AutonomousOrchestration.Agent("fx","Kurs","fx",List.of(),List.of()),new AutonomousOrchestration.Agent("weather","Pogoda","weather",List.of(),List.of()));var root=tasks.submitOrchestration("alice","conversation","request",original,agents,Set.of());
   assertThat(waiting.await(2,TimeUnit.SECONDS)).isTrue();assertThat(independent.await(2,TimeUnit.SECONDS)).isTrue();long independentDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(tasks.list("alice").stream().filter(t->"PRIVATE_WORKER_fx".equals(t.result())||"PRIVATE_WORKER_weather".equals(t.result())).count()<2&&System.nanoTime()<independentDeadline)Thread.sleep(5);assertThat(tasks.list("alice").stream().filter(t->t.state().equals("WAITING_FOR_AUTHORIZATION")).count()).isEqualTo(1);assertThat(tasks.list("alice").stream().filter(t->"PRIVATE_WORKER_fx".equals(t.result())||"PRIVATE_WORKER_weather".equals(t.result())).count()).isEqualTo(2);
   release.countDown();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(tasks.get("alice",root.taskId()).state().equals("RUNNING")&&System.nanoTime()<deadline)Thread.sleep(10);
   assertThat(tasks.get("alice",root.taskId()).state()).isEqualTo("PARTIALLY_COMPLETED");assertThat(tasks.get("alice",root.taskId()).result()).startsWith("MAIN_JARVIS").doesNotContain("PRIVATE_WORKER");assertThat(tasks.state("alice").get("activeProcesses")).isEqualTo(0L);
   var events=tasks.replay("alice",0).events();assertThat(events.stream().filter(e->e.type().equals("assistant.message.created")).count()).isEqualTo(1);assertThat(events.stream().filter(e->e.type().equals("assistant.message.generation.finished")).count()).isEqualTo(1);assertThat(events.stream().filter(e->e.type().equals("task.waiting_for_authorization")).count()).isEqualTo(1);
   var evidence=org.mockito.ArgumentCaptor.forClass(String.class);verify(delivery,times(1)).synthesize(eq("alice"),eq("conversation"),evidence.capture(),any());assertThat(evidence.getValue()).contains("PRIVATE_WORKER_fx","PRIVATE_WORKER_weather","AUTHORIZATION_CANCELLED");
  }finally{release.countDown();}
 }
}
