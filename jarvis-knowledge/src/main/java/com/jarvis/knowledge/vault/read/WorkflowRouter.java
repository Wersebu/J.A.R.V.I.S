package com.jarvis.knowledge.vault.read;

import com.jarvis.knowledge.vault.KnowledgeVaultService;
import com.jarvis.knowledge.vault.index.StoredDocument;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Picks the one workflow a message is about, so Core can hand it to the model up front instead of the model
 * spending turns on FIND_WORKFLOW / READ_WORKFLOW. Hybrid search (BM25 + embeddings) ranks the candidates;
 * a workflow is routed only when the message also names one of its tags, aliases or title words, because the
 * fused score is a rank signal, not a calibrated relevance that could serve as a threshold.
 */
@Service
public class WorkflowRouter {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkflowRouter.class);
    private static final int MIN_WORD = 4;
    private static final int STEM = 5; // Polish inflection: "muzyka/muzykę", "polubione/polubionych" share 5 letters

    private final KnowledgeVaultService vault;

    public WorkflowRouter(KnowledgeVaultService vault) {
        this.vault = vault;
    }

    /**
     * Returns the best workflow the query explicitly refers to.
     *
     * @param query the user's words (possibly with the previous turns of a short follow-up)
     * @return the routed workflow, or empty when vault mode is off, nothing matches or the index is not ready
     */
    public Optional<WorkflowCandidate> route(String query) {
        if (query == null || query.isBlank() || !vault.active()) return Optional.empty();
        try {
            // Search ranks the candidates; every other workflow still gets the gate, because a phrase like
            // "co teraz leci?" may name a workflow's alias without matching any of its indexed text.
            List<WorkflowCandidate> candidates = new ArrayList<>(vault.findWorkflows(query, false));
            for (WorkflowCandidate any : vault.findWorkflows("", false))
                if (candidates.stream().noneMatch(c -> c.path().equals(any.path()))) candidates.add(any);
            for (WorkflowCandidate candidate : candidates) {
                Optional<StoredDocument> document = vault.indexedDocument(candidate.path());
                if (document.isPresent() && (mentions(query, keywords(document.get())) || saysAlias(query, document.get().aliases())))
                    return Optional.of(candidate);
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[WORKFLOW_ROUTER] routing skipped: {}", exception.getMessage());
        }
        return Optional.empty();
    }

    static List<String> keywords(StoredDocument document) {
        List<String> words = new ArrayList<>();
        document.tags().forEach(tag -> words.addAll(words(tag)));
        document.aliases().forEach(alias -> words.addAll(words(alias)));
        words.addAll(words(document.title()));
        return words.stream().filter(word -> word.length() >= MIN_WORD).distinct().toList();
    }

    static boolean mentions(String query, List<String> keywords) {
        List<String> tokens = words(query).stream().filter(word -> word.length() >= MIN_WORD).toList();
        for (String keyword : keywords) {
            String stem = keyword.substring(0, Math.min(STEM, keyword.length()));
            if (tokens.stream().anyMatch(token -> token.startsWith(stem) || keyword.startsWith(token.substring(0, Math.min(STEM, token.length())))
                    && token.length() >= STEM)) return true;
        }
        return false;
    }

    /** An alias as a whole phrase ("co leci", "jaka to piosenka"): short everyday words that a stem match would overreach on. */
    static boolean saysAlias(String query, List<String> aliases) {
        String text = " " + String.join(" ", words(query)) + " ";
        return aliases.stream().map(alias -> String.join(" ", words(alias))).filter(alias -> !alias.isBlank())
                .anyMatch(alias -> text.contains(" " + alias + " "));
    }

    private static List<String> words(String text) {
        String normalized = Normalizer.normalize(String.valueOf(text).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replace('ł', 'l');
        return Arrays.stream(normalized.split("[^a-z0-9]+")).filter(word -> !word.isBlank()).toList();
    }
}
