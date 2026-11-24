package com.jarvis.api.companion;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.auth.JarvisAuthInterceptor;
import com.jarvis.api.service.ChatService;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.memory.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CompanionHttpTest {
    @Test void authenticationSubmissionSnapshotAndCrossOwnerAccess() throws Exception {
        ChatService chat=mock(ChatService.class);when(chat.chat(any())).thenReturn(new ChatResponse("done"));
        JarvisAuthService auth=mock(JarvisAuthService.class);when(auth.authenticateBearer(any())).thenReturn(Optional.empty());
        for(String owner:new String[]{"alice","bob"})when(auth.authenticateBearer("Bearer "+owner)).thenReturn(Optional.of(new UserAccount(owner,owner+"@example.test",owner,"USER",true,Instant.now(),Instant.now())));
        try(var tasks=new CompanionTasks(chat,1)){
            var mvc=MockMvcBuilders.standaloneSetup(new CompanionController(tasks)).addInterceptors(new JarvisAuthInterceptor(auth)).build();
            mvc.perform(get("/api/v1/companion/tasks")).andExpect(status().isUnauthorized());
            var response=mvc.perform(post("/api/v1/companion/tasks").header("Authorization","Bearer alice").contentType("application/json").content("{\"message\":\"hello\"}")).andExpect(status().isAccepted()).andReturn();
            String id=new ObjectMapper().readTree(response.getResponse().getContentAsString()).path("taskId").asText();
            mvc.perform(get("/api/v1/companion/tasks/"+id).header("Authorization","Bearer bob")).andExpect(status().isNotFound());
            mvc.perform(post("/api/v1/companion/tasks/"+id+"/cancel").header("Authorization","Bearer bob")).andExpect(status().isNotFound());
            mvc.perform(get("/api/v1/companion/state").header("Authorization","Bearer alice")).andExpect(status().isOk()).andExpect(jsonPath("$.serverInstanceId").value(tasks.serverInstanceId())).andExpect(jsonPath("$.tasks[0].taskId").value(id)).andExpect(jsonPath("$.latestSequence").isNumber());
            mvc.perform(post("/api/v1/companion/tasks").header("Authorization","Bearer alice").contentType("application/json").content("{\"message\":\"hello\",\"maxAttempts\":2}")).andExpect(status().isBadRequest());
        }
    }
}
