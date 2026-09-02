package com.jarvis.api.controller;

import com.jarvis.api.service.CodingService;
import com.jarvis.common.auth.CurrentUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodingControllerTest {

    @AfterEach
    void tearDown() {
        CurrentUserContext.clear();
    }

    @Test
    void startTaskBuildsRequestContextFromAuthenticatedUserContext() {
        CodingService codingService = mock(CodingService.class);
        CodingController controller = new CodingController(codingService);
        CodingService.CodingTask task = new CodingService.CodingTask(
                "task-1",
                "workspace-1",
                "conversation-1",
                "model",
                "prompt",
                CodingService.CodingTaskStatus.CREATED,
                List.of(),
                "created",
                0,
                Instant.now(),
                null,
                Map.of(),
                "",
                "",
                "",
                "user-a",
                Instant.now(),
                "",
                "coding-agent-v1",
                "",
                new CodingService.GitSnapshot("", "", "", ""),
                new CodingService.GitSnapshot("", "", "", "")
        );
        when(codingService.startTask(any(), any())).thenReturn(task);
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setSession(new org.springframework.mock.web.MockHttpSession(null, "session-1"));

        CurrentUserContext.runAs("user-a", () -> controller.startTask(
                new CodingService.StartTaskRequest("workspace-1", "conversation-1", "model", "prompt"),
                httpRequest
        ));

        ArgumentCaptor<CodingService.CodingRequestContext> context = ArgumentCaptor.forClass(CodingService.CodingRequestContext.class);
        verify(codingService).startTask(any(), context.capture());
        assertThat(context.getValue().userId()).isEqualTo("user-a");
        assertThat(context.getValue().sessionId()).isEqualTo("session-1");
        assertThat(context.getValue().conversationId()).isEqualTo("conversation-1");
    }
}
