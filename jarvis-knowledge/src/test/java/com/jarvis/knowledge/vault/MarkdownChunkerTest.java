package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.vault.chunk.HeuristicTokenCounter;
import com.jarvis.knowledge.vault.chunk.MarkdownChunker;
import com.jarvis.knowledge.vault.chunk.TokenCounter;
import com.jarvis.knowledge.vault.chunk.VaultChunk;
import com.jarvis.knowledge.vault.note.VaultMarkdownParser;
import com.jarvis.knowledge.vault.note.VaultNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownChunkerTest {

    private final VaultMarkdownParser parser = new VaultMarkdownParser();

    @Test
    void splitsByHeadingsKeepsHierarchyAndExactLineRanges() {
        String content = """
                ---
                title: Serwer
                ---
                # Serwer

                Wstęp o serwerze.

                ## Sprzęt

                Karta graficzna RTX 4060 Ti 16 GB.

                ### Pamięć

                64 GB RAM DDR5.

                ## Oprogramowanie

                Ubuntu Server 24.04 i Ollama.
                """;
        VaultNote note = parser.parse("projects/Serwer.md", content);
        List<VaultChunk> chunks = new MarkdownChunker(new HeuristicTokenCounter(), 60, 512, 0).chunk(note, "passage: ");

        assertThat(chunks).extracting(VaultChunk::headingBreadcrumb)
                .containsExactly("Serwer", "Serwer > Sprzęt", "Serwer > Sprzęt > Pamięć", "Serwer > Oprogramowanie");
        List<String> lines = note.lines();
        for (VaultChunk chunk : chunks) {
            String fromFile = String.join("\n", lines.subList(chunk.startLine() - 1, chunk.endLine())).strip();
            assertThat(chunk.text()).isEqualTo(fromFile);
        }
        assertThat(chunks.get(2).startLine()).isEqualTo(12);
        assertThat(chunks.get(2).text()).contains("64 GB RAM");
        assertThat(chunks.getFirst().embeddingText()).startsWith("passage: Serwer");
    }

    @Test
    void neverExceedsTheModelTokenLimitMeasuredByTheModelCounter() {
        // A strict counter: one token per character - characters are NOT assumed to be cheap.
        TokenCounter perCharacter = text -> text.length();
        StringBuilder longSection = new StringBuilder("# Długi dokument\n\n## Sekcja\n\n");
        for (int index = 0; index < 120; index++) {
            longSection.append("Zdanie numer ").append(index).append(" opisuje szczegóły konfiguracji serwera. ");
        }
        longSection.append("\n\n```\n# komentarz w kodzie, nie nagłówek\nkod\n```\n");
        VaultNote note = parser.parse("a.md", longSection.toString());

        List<VaultChunk> chunks = new MarkdownChunker(perCharacter, 300, 400, 0).chunk(note, "passage: ");

        assertThat(chunks).hasSizeGreaterThan(5);
        assertThat(chunks).allSatisfy(chunk -> assertThat(perCharacter.count(chunk.embeddingText())).isLessThanOrEqualTo(400));
        assertThat(chunks).noneMatch(chunk -> chunk.headingPath().contains("komentarz w kodzie, nie nagłówek"));
        String joined = String.join(" ", chunks.stream().map(VaultChunk::text).toList());
        assertThat(joined).contains("Zdanie numer 0 ").contains("Zdanie numer 119 ").contains("# komentarz w kodzie");
    }

    @Test
    void heuristicCounterOverestimatesComparedToCharactersPerFourRule() {
        HeuristicTokenCounter counter = new HeuristicTokenCounter();
        String polish = "Procedura planowania grafiku audytów sklepów obejmuje geolokalizację i optymalizację tras.";

        assertThat(counter.count(polish)).isGreaterThan(polish.length() / 4);
    }
}
