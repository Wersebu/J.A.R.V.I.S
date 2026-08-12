package com.jarvis.memory.pipeline;

import com.jarvis.common.dto.ChatRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests proving Core never retrieves Knowledge Workspace content automatically.
 * Knowledge access is exclusively model-driven through the knowledge native tool.
 */
class KnowledgeRetrievalStageTest {

    @Test
    void neverRetrievesEvenWhenMessageContainsKnowledgeKeywords() {
        KnowledgeRetrievalStage stage = new KnowledgeRetrievalStage();
        ChatRequest request = new ChatRequest(
                "conversation",
                "Jakie ustalenia mamy zapisane w dokumentacji projektu Nova? Sprawdz plik wiedzy.",
                Instant.now()
        );
        PipelineContext context = PipelineContext.initial("conversation", "request", request, event -> { }, event -> { });

        PipelineContext result = stage.execute(context);

        assertThat(result.retrievalResult()).isNotNull();
        assertThat(result.retrievalResult().documents()).isEmpty();
    }

    @Test
    void neverRetrievesForSimpleConversation() {
        KnowledgeRetrievalStage stage = new KnowledgeRetrievalStage();
        ChatRequest request = new ChatRequest("conversation", "Opowiedz krotka bajke o smoku.", Instant.now());
        PipelineContext context = PipelineContext.initial("conversation", "request", request, event -> { }, event -> { });

        PipelineContext result = stage.execute(context);

        assertThat(result.retrievalResult().documents()).isEmpty();
    }
}
