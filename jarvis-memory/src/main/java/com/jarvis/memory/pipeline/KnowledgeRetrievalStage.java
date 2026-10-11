package com.jarvis.memory.pipeline;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.knowledge.retrieval.RetrievalDocument;
import com.jarvis.knowledge.retrieval.RetrievalResult;
import com.jarvis.knowledge.vault.read.WorkflowCandidate;
import com.jarvis.knowledge.vault.read.WorkflowRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Preloads the one workflow the message is about (e.g. workflows/Spotify.md for "odpal coś na spotify") into the
 * knowledge context, so the model knows its tools and procedure from the first turn instead of spending turns
 * searching for files. Everything else in the Knowledge Workspace stays model-driven through the knowledge tool.
 */
@Service
@Order(60)
public class KnowledgeRetrievalStage implements PipelineStage {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeRetrievalStage.class);
    /** Follow-ups this short ("next", "co to za piosenka?") are read together with the previous user turns. */
    private static final int SHORT_FOLLOW_UP_WORDS = 4;
    private static final int PREVIOUS_TURNS = 2;

    private final WorkflowRouter router;

    public KnowledgeRetrievalStage() {
        this((WorkflowRouter) null);
    }

    @Autowired
    public KnowledgeRetrievalStage(ObjectProvider<WorkflowRouter> router) {
        this(router.getIfAvailable());
    }

    KnowledgeRetrievalStage(WorkflowRouter router) {
        this.router = router;
    }

    @Override
    public String name() {
        return "KnowledgeRetrievalStage";
    }

    @Override
    public PipelineContext execute(PipelineContext context) {
        String message = context.request().message() == null ? "" : context.request().message().trim();
        if (router == null || message.isBlank()) {
            return context.withRetrievalResult(new RetrievalResult(message, 0, 0, List.of()));
        }
        long started = System.nanoTime();
        String query = routingQuery(message, context.conversation());
        Optional<WorkflowCandidate> workflow = router.route(query);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        LOGGER.info("[KNOWLEDGE_PRELOAD] requestId={} query=\"{}\" workflow={} elapsedMs={}", context.requestId(), query,
                workflow.map(WorkflowCandidate::path).orElse("-"), elapsedMs);
        List<RetrievalDocument> documents = workflow.map(candidate -> List.of(new RetrievalDocument(
                documentId(candidate), candidate.title(), "workflow", candidate.path(), 1, ""))).orElse(List.of());
        return context.withRetrievalResult(new RetrievalResult(query, elapsedMs, documents.size(), documents));
    }

    private static String routingQuery(String message, List<ConversationMessage> conversation) {
        if (message.split("\\s+").length > SHORT_FOLLOW_UP_WORDS || conversation == null) return message;
        List<String> previous = new ArrayList<>();
        for (int i = conversation.size() - 1; i >= 0 && previous.size() < PREVIOUS_TURNS; i--) {
            ConversationMessage turn = conversation.get(i);
            if (turn.role() == MessageRole.USER && turn.content() != null && !turn.content().isBlank()
                    && !turn.content().strip().equals(message)) previous.add(0, turn.content().strip());
        }
        previous.add(message);
        return String.join(" ", previous);
    }

    private static UUID documentId(WorkflowCandidate candidate) {
        try {
            return UUID.fromString(candidate.documentId());
        } catch (IllegalArgumentException notUuid) {
            return UUID.nameUUIDFromBytes(candidate.path().getBytes(StandardCharsets.UTF_8));
        }
    }
}
