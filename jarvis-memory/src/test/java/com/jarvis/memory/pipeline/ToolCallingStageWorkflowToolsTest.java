package com.jarvis.memory.pipeline;

import com.jarvis.common.context.KnowledgeContext;
import com.jarvis.common.context.KnowledgeSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallingStageWorkflowToolsTest {

    private static final KnowledgeContext SPOTIFY = new KnowledgeContext(
            "Source: workflows/Spotify.md\n| odpal coś | pc__spotify_play source=liked |\n| next | pc__spotify_control action=next |\n"
                    + "Low-level reads: pc__spotify action=liked. Not pc__media.",
            List.of(new KnowledgeSource(UUID.randomUUID(), "Spotify — odtwarzanie i biblioteka", "workflows/Spotify.md", "workflow", 200)),
            1, 200, 50, false, 1);

    @Test
    void toolsNamedByThePreloadedWorkflowAreCollected() {
        assertThat(ToolCallingStage.workflowTools(SPOTIFY))
                .containsExactly("pc__spotify_play", "pc__spotify_control", "pc__spotify", "pc__media");
        assertThat(ToolCallingStage.workflowTools(KnowledgeContext.empty())).isEmpty();
    }

    @Test
    void aTopicKeptWorkflowNarrowsToolsOnlyWhenTheModelsGoalIsThatTopic() {
        assertThat(ToolCallingStage.goalNamesWorkflow("Play the track \"K I C I K I — WRONA\" on Spotify", SPOTIFY)).isTrue();
        assertThat(ToolCallingStage.goalNamesWorkflow("Check tomorrow's weather in Kraków", SPOTIFY)).isFalse();
    }
}
