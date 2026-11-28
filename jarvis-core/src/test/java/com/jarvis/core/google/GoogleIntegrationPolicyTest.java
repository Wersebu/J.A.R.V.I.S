package com.jarvis.core.google;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.companion.*;
import com.jarvis.tools.*;
import com.jarvis.tools.integration.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GoogleIntegrationPolicyTest {
 GoogleOAuth oauth;UserDevice device;GoogleIntegrationPolicy policy;InMemoryCompanionStore store;ToolRequest request;DefaultToolManager tools;JarvisTool tool;
 @BeforeEach void setup(){CurrentUserContext.set("alice");oauth=mock(GoogleOAuth.class);device=mock(UserDevice.class);store=new InMemoryCompanionStore();ObjectProvider<CompanionDocumentStore> documents=mock(ObjectProvider.class);when(documents.getIfAvailable()).thenReturn(store);ObjectProvider<CompanionEventSink> events=mock(ObjectProvider.class);policy=new GoogleIntegrationPolicy(oauth,device,documents,events);request=new ToolRequest("gmail","SEARCH","conversation","request","","",Map.of("limit",5));tool=mock(JarvisTool.class);when(tool.getName()).thenReturn("gmail");when(tool.execute(any())).thenReturn(new ToolResult(true,"gmail","SEARCH","request","conversation",false,List.of(),"Read complete",Map.of("messages",List.of()),"","",false,""));tools=new DefaultToolManager(List.of(tool));ReflectionTestUtils.setField(tools,"authorizationPolicies",List.of(policy));}
 @AfterEach void cleanup(){CurrentUserContext.clear();}
 @Test void connectedReadExecutesImmediatelyWithoutAnyConsent(){assertThat(tools.execute(request).success()).isTrue();verifyNoInteractions(device);verify(tool,times(1)).execute(request);}
 @Test void authorizationResumesOriginalOperationExactlyOnceWithCorrelatedSavedState(){AtomicBoolean ready=new AtomicBoolean();doAnswer(i->{if(!ready.get())throw new GoogleException("MISSING_SCOPE","missing");return null;}).when(oauth).requireScopes(eq("alice"),eq(GoogleService.GMAIL),anySet());doAnswer(i->{ready.set(true);return null;}).when(device).connectGoogle(eq("alice"),eq(GoogleService.GMAIL),anySet());var states=new ArrayList<String>();TaskExecutionContext.run("task-id",(state,detail)->states.add(state),()->assertThat(tools.execute(request).success()).isTrue());assertThat(states).containsExactly("WAITING_FOR_AUTHORIZATION","RUNNING");verify(tool,times(1)).execute(request);verify(device,times(1)).connectGoogle(eq("alice"),eq(GoogleService.GMAIL),anySet());var saved=store.documents("alice","integration-authorization","");assertThat(saved).hasSize(1);assertThat(saved.getFirst().body()).contains("task-id","conversation","request","COMPLETED");assertThat(store.documents("bob","integration-authorization","")).isEmpty();}
 @Test void cancelledConsentIsTerminalAndCannotLoop(){doThrow(new GoogleException("DISCONNECTED","missing")).when(oauth).requireScopes(anyString(),any(),anySet());doThrow(new GoogleException("AUTHORIZATION_CANCELLED","Anulowano autoryzację.")).when(device).connectGoogle(anyString(),any(),anySet());assertThat(tools.execute(request).errorCode()).isEqualTo("AUTHORIZATION_CANCELLED");assertThat(tools.execute(request).errorCode()).isEqualTo("AUTHORIZATION_NOT_REPEATED");verify(device,times(1)).connectGoogle(anyString(),any(),anySet());verify(tool,never()).execute(any());}
 @Test void timedOutConsentDoesNotInvokeTheRead(){doThrow(new GoogleException("MISSING_SCOPE","missing")).when(oauth).requireScopes(anyString(),any(),anySet());doThrow(new GoogleException("AUTHORIZATION_TIMEOUT","Upłynął czas autoryzacji.")).when(device).connectGoogle(anyString(),any(),anySet());assertThat(tools.execute(request).errorCode()).isEqualTo("AUTHORIZATION_TIMEOUT");verify(tool,never()).execute(any());}
 @Test void transientFailureDoesNotReopenOAuth(){doThrow(new GoogleException("TEMPORARILY_UNAVAILABLE","temporary")).when(oauth).requireScopes(anyString(),any(),anySet());assertThat(tools.execute(request).errorCode()).isEqualTo("TEMPORARILY_UNAVAILABLE");verifyNoInteractions(device);}
 @Test void emptyMailboxIsAValidSuccessfulResult(){assertThat(tools.execute(request).data()).containsEntry("resultKind","EMPTY_RESULT").containsEntry("executionState","COMPLETED");}
 @Test void statusOnlyNeverStartsOAuth(){var status=new ToolRequest("gmail","STATUS","c","r","","",Map.of());policy.prepare(status);verifyNoInteractions(device,oauth);}
 @Test void writesAreNotRetriedAfterProviderFailure(){var write=new ToolRequest("gmail","CREATE_DRAFT","c","r","","",Map.of());var failure=new ToolResult(false,"gmail","CREATE_DRAFT","r","c",false,List.of(),"Missing scope",Map.of(),"MISSING_SCOPE","",false,"");assertThat(policy.recoverRead(write,failure)).isFalse();verifyNoInteractions(device);}

 @Test void queuedConsentCanBeCancelledWithoutOpeningAnotherWindow()throws Exception{
  doThrow(new GoogleException("DISCONNECTED","missing")).when(oauth).requireScopes(anyString(),any(),anySet());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var queued=new CountDownLatch(1);var code=new AtomicReference<String>();
  doAnswer(i->{entered.countDown();release.await(3,TimeUnit.SECONDS);return null;}).when(device).connectGoogle(anyString(),any(),anySet());
  Thread first=Thread.ofVirtual().start(()->CurrentUserContext.runAs("alice",()->{try{policy.prepare(request);}catch(RuntimeException expected){}}));
  try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();var secondRequest=new ToolRequest("gmail","SEARCH","conversation","other-request","","",Map.of());Thread second=Thread.ofVirtual().start(()->CurrentUserContext.runAs("alice",()->TaskExecutionContext.run("other-task",(state,detail)->{if(state.equals("WAITING_FOR_AUTHORIZATION"))queued.countDown();},()->{try{policy.prepare(secondRequest);}catch(IntegrationAccessException failure){code.set(failure.code());}})));
   assertThat(queued.await(1,TimeUnit.SECONDS)).isTrue();second.interrupt();second.join(1000);assertThat(second.isAlive()).isFalse();assertThat(code.get()).isEqualTo("CANCELLED");verify(device,times(1)).connectGoogle(anyString(),any(),anySet());
  }finally{release.countDown();first.join(2000);}
 }

 @Test void concurrentMissingScopeResponsesShareTheCompletedAuthorization()throws Exception{
  var revision=new AtomicReference<String>("old");when(oauth.authorizationRevision(anyString(),any())).thenAnswer(i->revision.get());var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var queued=new CountDownLatch(1);var outcomes=new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
  doAnswer(i->{entered.countDown();release.await(2,TimeUnit.SECONDS);revision.set("new");return null;}).when(device).connectGoogle(anyString(),any(),anySet());
  var failure=new ToolResult(false,"gmail","SEARCH","r","c",false,List.of(),"Missing",Map.of(),"MISSING_SCOPE","",false,"");
  Thread first=Thread.ofVirtual().start(()->CurrentUserContext.runAs("alice",()->outcomes.add(policy.recoverRead(request,failure))));
  Thread second=null;try{assertThat(entered.await(1,TimeUnit.SECONDS)).isTrue();var other=new ToolRequest("gmail","SEARCH","conversation","other-request","","",Map.of());second=Thread.ofVirtual().start(()->CurrentUserContext.runAs("alice",()->TaskExecutionContext.run("task-2",(state,detail)->{if(state.equals("WAITING_FOR_AUTHORIZATION"))queued.countDown();},()->outcomes.add(policy.recoverRead(other,failure)))));assertThat(queued.await(1,TimeUnit.SECONDS)).isTrue();release.countDown();first.join(2000);second.join(2000);assertThat(outcomes).containsExactly(true,true);assertThat(policy.recoverRead(new ToolRequest("gmail","SEARCH","conversation","late-response","","",Map.of()),failure)).isTrue();verify(device,times(1)).connectGoogle(anyString(),any(),anySet());}
  finally{release.countDown();first.join(2000);if(second!=null)second.join(2000);}
 }
}
