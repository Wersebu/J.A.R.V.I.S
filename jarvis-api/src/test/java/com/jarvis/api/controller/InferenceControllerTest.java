package com.jarvis.api.controller;
import com.jarvis.common.ai.*;
import com.jarvis.memory.auth.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InferenceControllerTest {
 private InferenceController controller(InferenceControl control){ObjectProvider<InferenceControl> p=mock(ObjectProvider.class);when(p.getIfAvailable()).thenReturn(control);return new InferenceController(p);}
 private MockHttpServletRequest request(String role,boolean active){var r=new MockHttpServletRequest();r.setAttribute("jarvis.user",new UserAccount("u","u@test","User",role,active,Instant.now(),Instant.now()));return r;}
 @Test void missingIdentityIsDenied(){var control=mock(InferenceControl.class);assertThatThrownBy(()->controller(control).state(new MockHttpServletRequest())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");verifyNoInteractions(control);}
 @Test void ordinaryUserCannotSwitchOrReadDiagnostics(){var control=mock(InferenceControl.class);var c=controller(control);assertThatThrownBy(()->c.select(new InferenceController.SwitchRequest("llamacpp","a.gguf",Map.of()),request("USER",true))).hasMessageContaining("403");assertThatThrownBy(()->c.state(request("USER",true))).hasMessageContaining("403");verifyNoInteractions(control);}
 @Test void inactiveAdminIsDenied(){var control=mock(InferenceControl.class);assertThatThrownBy(()->controller(control).state(request("ADMIN",false))).hasMessageContaining("403");verifyNoInteractions(control);}
 @Test void adminUsesTypedControl(){var control=mock(InferenceControl.class);when(control.state()).thenReturn(Map.of("status","READY"));assertThat(controller(control).state(request("ADMIN",true))).containsEntry("status","READY");}
 @Test void optionalIntegrationLauncherDoesNotNeedEngineBean(){assertThatThrownBy(()->controller(null).state(request("ADMIN",true))).hasMessageContaining("503");}
}
