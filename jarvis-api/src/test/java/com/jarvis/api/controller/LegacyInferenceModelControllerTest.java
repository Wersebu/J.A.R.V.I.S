package com.jarvis.api.controller;
import com.jarvis.common.ai.*;
import com.jarvis.common.model.ActiveModelService;
import com.jarvis.api.dto.ActiveModelRequest;
import com.jarvis.memory.auth.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class LegacyInferenceModelControllerTest {
 private MockHttpServletRequest request(String role){var r=new MockHttpServletRequest();r.setAttribute("jarvis.user",new UserAccount("u","u@test","User",role,true,Instant.now(),Instant.now()));return r;}
 @Test void legacyEndpointUsesSameTransactionalSwitchToOllama(){var active=mock(ActiveModelService.class);var control=mock(InferenceControl.class);ObjectProvider<InferenceControl> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(control);when(control.selection()).thenReturn(new InferenceControl.Selection("id","ollama","existing",Map.of()));var c=new ModelController(active);c.inference(p);assertThat(c.setActiveModel(new ActiveModelRequest("existing"),request("ADMIN")).getStatusCode().value()).isEqualTo(200);verify(control).switchTo("ollama","existing",Map.of());verify(active,never()).switchTo(any());}
 @Test void legacyEndpointCannotBypassAdminAuthorization(){var active=mock(ActiveModelService.class);var c=new ModelController(active);assertThatThrownBy(()->c.setActiveModel(new ActiveModelRequest("existing"),request("USER"))).hasMessageContaining("403");verifyNoInteractions(active);}
}
