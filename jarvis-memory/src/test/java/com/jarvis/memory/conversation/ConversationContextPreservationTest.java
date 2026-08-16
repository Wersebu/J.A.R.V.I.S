package com.jarvis.memory.conversation;

import com.jarvis.common.memory.ConversationMessage;
import com.jarvis.common.memory.MessageRole;
import com.jarvis.common.prompt.GroundingSource;
import com.jarvis.common.prompt.GroundingSourceType;
import com.jarvis.common.prompt.PersonalQueryAnalysis;
import com.jarvis.common.prompt.PromptContext;
import com.jarvis.memory.grounding.DefaultPromptContextFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test proving a short follow-up reply (e.g. answering a clarifying "what's your
 * starting point?" with just an address) does not lose the original task context. A long prior
 * assistant turn (e.g. listing many addresses extracted from an earlier image) must survive in
 * full into the next turn's built prompt via the existing conversation-history replay mechanism
 * ({@link RecentWindowConversationContextReducer} + {@link DefaultPromptContextFactory}) - no
 * additional per-conversation persistence is needed for this to work, since the model's own prior
 * answer already carries the information forward as plain text.
 */
class ConversationContextPreservationTest {

    @Test
    void longAssistantAnswerListingManyAddressesSurvivesIntactIntoTheNextTurnsPrompt() {
        ConversationHistoryProperties properties = new ConversationHistoryProperties(true, 30, 30_000, false, false, false);
        RecentWindowConversationContextReducer reducer = new RecentWindowConversationContextReducer(properties);
        DefaultPromptContextFactory factory = new DefaultPromptContextFactory(message -> PersonalQueryAnalysis.none());

        StringBuilder addressList = new StringBuilder("Odczytane adresy sklepow:\n");
        for (int i = 1; i <= 15; i++) {
            addressList.append(i).append(". Biedronka, ul. Testowa ").append(i).append(", 0").append(1000 + i).append(" Miasto\n");
        }
        String originalAnswer = addressList.toString();

        List<ConversationMessage> history = List.of(
                new ConversationMessage(MessageRole.USER, "Oto zdjecie z adresami sklepow.", Instant.now()),
                new ConversationMessage(MessageRole.ASSISTANT, originalAnswer, Instant.now()),
                new ConversationMessage(MessageRole.USER,
                        "Pogrupuj sklepy wedlug lokalizacji i zaproponuj kolejnosc wyjazdow.", Instant.now()),
                new ConversationMessage(MessageRole.ASSISTANT, "Jaki jest punkt startowy?", Instant.now())
        );

        // The reducer's 30-message/30 000-char window must not have to drop anything for a
        // conversation this short - this is the "did the reducer trim it away" half of the check.
        List<ConversationMessage> reduced = reducer.reduce(history, "current-request-id");
        assertThat(reduced).hasSize(4);

        // The next turn is the short follow-up reply itself - "Nowa Wola 05-500" - with no
        // repeated addresses or goal restatement from the user.
        PromptContext context = factory.create("Nowa Wola 05-500", null, null, reduced);

        GroundingSource addressSource = context.groundingSources().stream()
                .filter(source -> source.type() == GroundingSourceType.CONVERSATION)
                .filter(source -> source.contentPreview().contains("Biedronka, ul. Testowa 1,"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Original address list not found in conversation context"));

        // Not just the first address - the full list, including the last entry, must be intact
        // (this is the "did the 4 000-char preview cap truncate it" half of the check).
        assertThat(addressSource.contentPreview()).contains("Biedronka, ul. Testowa 15,");
        assertThat(addressSource.title()).isEqualTo(MessageRole.ASSISTANT.name());
    }
}
