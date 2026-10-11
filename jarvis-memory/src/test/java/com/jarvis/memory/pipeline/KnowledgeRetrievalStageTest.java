package com.jarvis.memory.pipeline;

import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.knowledge.vault.read.WorkflowCandidate;
import com.jarvis.knowledge.vault.read.WorkflowRouter;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without a workflow router (LEGACY mode) Core retrieves nothing automatically; with one it preloads only the
 * single workflow the message is about.
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
    void preloadsTheRoutedWorkflowAndReadsShortFollowUpsWithThePreviousTurn() {
        List<String> queries = new ArrayList<>();
        WorkflowRouter router = new WorkflowRouter(null) {
            @Override
            public Optional<WorkflowCandidate> route(String query) {
                queries.add(query);
                return query.contains("spotify") ? Optional.of(new WorkflowCandidate("not-a-uuid", "workflows/Spotify.md",
                        "Spotify", "active", "1", "", 900, 1, 0.03, List.of())) : Optional.empty();
            }
        };
        KnowledgeRetrievalStage stage = new KnowledgeRetrievalStage(router);
        PipelineContext context = PipelineContext.initial("conversation", "request",
                        new ChatRequest("conversation", "next", Instant.now()), event -> { }, event -> { })
                .withConversation(List.of(new ConversationMessage(MessageRole.USER, "odpal coś na spotify", Instant.now()),
                        new ConversationMessage(MessageRole.ASSISTANT, "Gra Tygrys.", Instant.now())));

        PipelineContext result = stage.execute(context);

        assertThat(queries).containsExactly("odpal coś na spotify next");
        assertThat(result.retrievalResult().documents()).singleElement()
                .satisfies(document -> assertThat(document.relativePath()).isEqualTo("workflows/Spotify.md"));
    }

    @Test
    void shortFollowUpKeepsTheConversationsRecentWorkflowButALongerOtherTopicDoesNot() {
        WorkflowRouter router = new WorkflowRouter(null) {
            @Override
            public Optional<WorkflowCandidate> route(String query) {
                return query.contains("spotify") ? Optional.of(new WorkflowCandidate("id", "workflows/Spotify.md",
                        "Spotify", "active", "1", "", 900, 1, 0.03, List.of())) : Optional.empty();
            }
        };
        KnowledgeRetrievalStage stage = new KnowledgeRetrievalStage(router);

        assertThat(paths(stage.execute(context("odpal coś na spotify")))).containsExactly("workflows/Spotify.md");
        assertThat(paths(stage.execute(context("wznów")))).containsExactly("workflows/Spotify.md");
        assertThat(paths(stage.execute(context("jebnij basem!")))).containsExactly("workflows/Spotify.md");
        assertThat(paths(stage.execute(context("napisz mi maila do szefa o urlopie w przyszłym tygodniu")))).isEmpty();
    }

    private static PipelineContext context(String message) {
        return PipelineContext.initial("conversation", "request", new ChatRequest("conversation", message, Instant.now()),
                event -> { }, event -> { });
    }

    private static List<String> paths(PipelineContext context) {
        return context.retrievalResult().documents().stream().map(document -> document.relativePath()).toList();
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
