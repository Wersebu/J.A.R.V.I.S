package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.vault.note.VaultLinkResolver;
import com.jarvis.knowledge.vault.note.VaultMarkdownParser;
import com.jarvis.knowledge.vault.note.VaultNote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VaultMarkdownParserTest {

    private final VaultMarkdownParser parser = new VaultMarkdownParser();

    @Test
    void readsFrontmatterAndKeepsOriginalLineNumbers() {
        VaultNote note = parser.parse("projects/Serwer domowy.md", """
                ---
                id: proj-serwer
                title: Serwer domowy
                type: project
                tags: [infrastruktura, Ubuntu]
                status: active
                version: 3
                updated: 2026-09-30
                ---
                # Serwer domowy

                Karta graficzna to RTX 4060 Ti 16 GB. #gpu
                """);

        assertThat(note.frontmatterId()).isEqualTo("proj-serwer");
        assertThat(note.title()).isEqualTo("Serwer domowy");
        assertThat(note.type()).isEqualTo("project");
        assertThat(note.status()).isEqualTo("active");
        assertThat(note.version()).isEqualTo("3");
        assertThat(note.updated()).isEqualTo("2026-09-30");
        assertThat(note.tags()).containsExactly("infrastruktura", "ubuntu", "gpu");
        assertThat(note.bodyStartLine()).isEqualTo(10);
        assertThat(note.headings()).singleElement().satisfies(heading -> assertThat(heading.line()).isEqualTo(10));
        assertThat(note.frontmatterError()).isEmpty();
    }

    @Test
    void documentWithoutFrontmatterUsesFirstHeadingOrFileName() {
        VaultNote withHeading = parser.parse("knowledge/Notatka bez metadanych.md", "# Zażółć gęślą jaźń\n\nTreść.\n");
        VaultNote plain = parser.parse("knowledge/Łódź – trasy.md", "Tylko tekst, bez nagłówka.\n");

        assertThat(withHeading.title()).isEqualTo("Zażółć gęślą jaźń");
        assertThat(withHeading.frontmatterId()).isEmpty();
        assertThat(withHeading.bodyStartLine()).isEqualTo(1);
        assertThat(plain.title()).isEqualTo("Łódź – trasy");
    }

    @Test
    void invalidYamlIsReportedButDocumentStillParses() {
        VaultNote note = parser.parse("a.md", "---\ntitle: [unclosed\n---\n# Tytuł\ntekst\n");

        assertThat(note.frontmatterError()).startsWith("Invalid YAML frontmatter");
        assertThat(note.title()).isEqualTo("Tytuł");
        assertThat(note.body()).contains("tekst");
    }

    @Test
    void extractsMarkdownLinksWikilinksAndEmbedsButNotCode() {
        VaultNote note = parser.parse("projects/Jarvis.md", """
                Zobacz [[Serwer domowy]], [[workflows/Audyt sklepów#Krok 2|procedurę]] i ![[schemat sieci.png]].
                Opis: [konfiguracja](../knowledge/Konfiguracja%20Ollama.md) oraz [strona](https://example.com).
                `[[to nie jest link]]`
                ```
                [[też nie]]
                ```
                """);

        assertThat(note.links()).extracting(VaultNote.Link::kind, VaultNote.Link::target)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("wikilink", "Serwer domowy"),
                        org.assertj.core.groups.Tuple.tuple("wikilink", "workflows/Audyt sklepów"),
                        org.assertj.core.groups.Tuple.tuple("embed", "schemat sieci.png"),
                        org.assertj.core.groups.Tuple.tuple("markdown", "../knowledge/Konfiguracja Ollama.md"));
        assertThat(note.links().get(1).anchor()).isEqualTo("Krok 2");
        assertThat(note.links().get(1).label()).isEqualTo("procedurę");
    }

    @Test
    void resolvesLinksLikeObsidian() {
        VaultLinkResolver resolver = new VaultLinkResolver(List.of(
                "projects/Serwer domowy.md", "knowledge/Konfiguracja Ollama.md", "attachments/schemat sieci.png",
                "workflows/Audyt sklepów.md"));
        VaultNote note = parser.parse("projects/Jarvis.md",
                "[[Serwer domowy]] [[workflows/Audyt sklepów#Krok 2]] ![[schemat sieci.png]] "
                        + "[k](../knowledge/Konfiguracja%20Ollama.md) [[Nie istnieje]]");

        assertThat(note.links()).extracting(link -> resolver.resolve(note.relativePath(), link).orElse("-"))
                .containsExactly("projects/Serwer domowy.md", "workflows/Audyt sklepów.md", "attachments/schemat sieci.png",
                        "-", "knowledge/Konfiguracja Ollama.md");
    }
}
