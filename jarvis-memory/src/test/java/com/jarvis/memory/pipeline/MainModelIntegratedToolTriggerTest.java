package com.jarvis.memory.pipeline;

import com.jarvis.common.ai.ImageAttachment;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.runtime.ToolIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving the current-message-attachments policy section only appears (and only
 * needs to appear) in the main-model prompt when the request actually carries images.
 */
class MainModelIntegratedToolTriggerTest {

    private final MainModelIntegratedToolTrigger trigger = new MainModelIntegratedToolTrigger(message -> ToolIntent.NO_TOOL);

    @Test
    void promptIncludesAttachmentPolicyWhenImagesArePresent() {
        PipelineContext context = PipelineContext.initial(
                        "conversation-1", "request-1",
                        new ChatRequest("conversation-1", "przygotuj grafik", null, KnowledgeMode.FAST, List.of()),
                        event -> { }, event -> { })
                .withImages(List.of(new ImageAttachment("base64data", "sklepy.png")));

        String prompt = trigger.buildMainModelPrompt(context);

        assertThat(prompt).contains("CURRENT MESSAGE ATTACHMENTS");
        assertThat(prompt).contains("Do not use KnowledgeTool to locate a current-message attachment");
    }

    @Test
    void promptOmitsAttachmentPolicyWhenThereAreNoImages() {
        PipelineContext context = PipelineContext.initial(
                "conversation-1", "request-1",
                new ChatRequest("conversation-1", "sprawdz w wiedzy jaka mam karte graficzna", null, KnowledgeMode.FAST, List.of()),
                event -> { }, event -> { });

        String prompt = trigger.buildMainModelPrompt(context);

        assertThat(prompt).doesNotContain("CURRENT MESSAGE ATTACHMENTS");
    }
}
