package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.KnowledgeException;
import com.jarvis.knowledge.vault.index.StoredDocument;
import com.jarvis.knowledge.vault.index.VaultIndexStatus;
import com.jarvis.knowledge.vault.read.PagedDocument;
import com.jarvis.knowledge.vault.read.WorkflowCandidate;
import com.jarvis.knowledge.vault.search.VaultSearchHit;
import com.jarvis.knowledge.vault.search.VaultSearchQuery;
import com.jarvis.knowledge.vault.search.VaultSearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultIndexAndSearchTest {

    @TempDir
    Path temp;

    private final List<KnowledgeVaultService> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(KnowledgeVaultService::close);
    }

    private KnowledgeVaultService open(TestVault vault, TestEmbeddingBackends.Hashing backend, String model) {
        KnowledgeVaultService service = vault.service(backend, model);
        opened.add(service);
        service.startSynchronously();
        return service;
    }

    private static String longDocument(String lastFact) {
        StringBuilder text = new StringBuilder("---\nid: kb-dlugi\ntype: knowledge\n---\n# Dziennik serwera\n\n");
        for (int section = 1; section <= 40; section++) {
            text.append("## Wpis ").append(section).append("\n\n");
            text.append("Rutynowa kontrola numer ").append(section)
                    .append(": sprawdzono dyski, temperatury, aktualizacje pakietów oraz kopie zapasowe bazy. ")
                    .append("Wszystko działa poprawnie, bez zmian w konfiguracji usług.\n\n");
        }
        text.append("## Wpis końcowy\n\n").append(lastFact).append("\n");
        return text.toString();
    }

    @Test
    void findsInformationAtTheEndOfALongDocument() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/Dziennik serwera.md", longDocument(
                "Klucz awaryjnego dostępu trzymamy w sejfie w gabinecie, szuflada numer siedem."));
        vault.write("knowledge/Inne.md", "# Inne\n\nNotatka o czymś zupełnie innym.\n");
        KnowledgeVaultService service = open(vault, null, "");

        VaultSearchResult result = service.search(VaultSearchQuery.of("gdzie jest sejf z kluczem awaryjnym"), "c1", "r1");

        assertThat(result.noResults()).isFalse();
        VaultSearchHit top = result.hits().getFirst();
        assertThat(top.path()).isEqualTo("knowledge/Dziennik serwera.md");
        assertThat(top.text()).contains("szuflada numer siedem");
        assertThat(top.startLine()).isGreaterThan(100);
        assertThat(top.headingPath()).containsExactly("Dziennik serwera", "Wpis końcowy");
        assertThat(result.contextTokens()).isLessThan(2500);
        assertThat(service.sources().forConversation("c1", 10)).extracting(VaultSourceTracker.SourceUse::path)
                .contains("knowledge/Dziennik serwera.md");
    }

    @Test
    void matchesPolishInflectedFormsAndDiacritics() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("workflows/Audyty sklepów.md", "# Audyty sklepów\n\nAudyt w jednym sklepie trwa około 45 minut.\n");
        vault.write("knowledge/Kawa.md", "# Kawa\n\nUlubiona kawa to espresso z Łodzi.\n");
        KnowledgeVaultService service = open(vault, null, "");

        assertThat(service.search(VaultSearchQuery.of("ile trwa audyt w sklepach"), null, null).hits().getFirst().path())
                .isEqualTo("workflows/Audyty sklepów.md");
        assertThat(service.search(VaultSearchQuery.of("lodz espresso"), null, null).hits().getFirst().path())
                .isEqualTo("knowledge/Kawa.md");
    }

    @Test
    void reportsNoResultsInsteadOfReturningUnrelatedFragments() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/Serwer.md", "# Serwer\n\nKarta graficzna RTX 4060 Ti 16 GB.\n");
        KnowledgeVaultService service = open(vault, null, "");

        VaultSearchResult result = service.search(VaultSearchQuery.of("przepis na pierogi z kapustą"), null, null);

        assertThat(result.noResults()).isTrue();
        assertThat(result.hits()).isEmpty();
        assertThat(result.notes()).anyMatch(note -> note.contains("Do not guess"));
    }

    @Test
    void updatesAndDeletesFragmentsWithoutDuplicates() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/Sieć.md", "# Sieć\n\nRouter stoi w piwnicy.\n");
        vault.write("knowledge/Usuwany.md", "# Usuwany\n\nTymczasowa notatka o drukarce laserowej.\n");
        TestEmbeddingBackends.Hashing backend = new TestEmbeddingBackends.Hashing(64);
        KnowledgeVaultService service = open(vault, backend, "m1");
        int chunksBefore = service.status().chunks();

        vault.rewrite("knowledge/Sieć.md", "# Sieć\n\nRouter przeniesiono na strych.\n");
        Files.delete(vault.root.resolve("knowledge/Usuwany.md"));
        Map<String, Integer> changes = service.reindex();
        service.embedPendingNow();

        assertThat(changes).containsEntry("changed", 1).containsEntry("removed", 1);
        assertThat(service.search(VaultSearchQuery.of("router piwnica"), null, null).hits())
                .allSatisfy(hit -> assertThat(hit.text()).doesNotContain("piwnicy"));
        assertThat(service.search(VaultSearchQuery.of("router strych"), null, null).hits().getFirst().text()).contains("strych");
        assertThat(service.search(VaultSearchQuery.of("drukarka laserowa"), null, null).hits())
                .noneMatch(hit -> hit.text().contains("drukarce"));
        assertThat(service.status().chunks()).isEqualTo(chunksBefore - 1);

        Map<String, Integer> again = service.reindex();
        assertThat(again).containsEntry("added", 0).containsEntry("changed", 0).containsEntry("removed", 0);
        assertThat(service.status().chunks()).isEqualTo(chunksBefore - 1);
        assertThat(service.status().pendingEmbeddings()).isZero();
    }

    @Test
    void keepsDocumentIdWhenAFileIsMovedOrRenamed() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("Inbox/Notatka o UPS.md", "# UPS\n\nZasilacz UPS podtrzymuje serwer przez 20 minut.\n");
        vault.write("Inbox/Projekt.md", "---\nid: proj-42\n---\n# Projekt\n\nOpis projektu.\n");
        TestEmbeddingBackends.Hashing backend = new TestEmbeddingBackends.Hashing(64);
        KnowledgeVaultService service = open(vault, backend, "m1");
        String upsId = service.indexedDocument("Inbox/Notatka o UPS.md").orElseThrow().documentId();
        int embeddedInputs = backend.inputs.get();

        Files.createDirectories(vault.root.resolve("knowledge/sprzęt"));
        Files.move(vault.root.resolve("Inbox/Notatka o UPS.md"), vault.root.resolve("knowledge/sprzęt/UPS.md"));
        Files.createDirectories(vault.root.resolve("projects"));
        Files.move(vault.root.resolve("Inbox/Projekt.md"), vault.root.resolve("projects/Projekt 42.md"));
        vault.rewrite("projects/Projekt 42.md", "---\nid: proj-42\n---\n# Projekt\n\nOpis projektu po zmianie.\n");
        Map<String, Integer> changes = service.reindex();
        service.embedPendingNow();

        assertThat(changes).containsEntry("moved", 2).containsEntry("removed", 0).containsEntry("added", 0);
        assertThat(service.indexedDocument("knowledge/sprzęt/UPS.md").orElseThrow().documentId()).isEqualTo(upsId);
        assertThat(service.indexedDocument("projects/Projekt 42.md").orElseThrow().documentId()).isEqualTo("proj-42");
        assertThat(service.indexedDocument("Inbox/Notatka o UPS.md")).isEmpty();
        assertThat(service.search(VaultSearchQuery.of("UPS podtrzymanie"), null, null).hits().getFirst().path())
                .isEqualTo("knowledge/sprzęt/UPS.md");
        // The UPS note moved unchanged: its fragment vector is reused; only the edited project fragment is re-embedded.
        assertThat(backend.embedded.subList(embeddedInputs, backend.embedded.size()))
                .filteredOn(text -> text.startsWith("passage: ")).singleElement()
                .satisfies(text -> assertThat(text).contains("po zmianie"));
    }

    @Test
    void survivesRestartWithoutReembedding() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/Ollama.md", "# Ollama\n\nModel czatu to gemma4:12b, embeddingi liczymy na CPU.\n");
        TestEmbeddingBackends.Hashing backend = new TestEmbeddingBackends.Hashing(64);
        KnowledgeVaultService first = vault.service(backend, "m1");
        first.startSynchronously();
        first.close();
        int inputsAfterFirstRun = backend.inputs.get();

        KnowledgeVaultService second = vault.service(backend, "m1");
        opened.add(second);
        second.startSynchronously();

        assertThat(backend.inputs.get()).isEqualTo(inputsAfterFirstRun);
        VaultIndexStatus status = second.status();
        assertThat(status.lastScanChanges()).containsEntry("unchanged", 1).containsEntry("added", 0);
        assertThat(status.embeddedChunks()).isEqualTo(status.chunks());
        VaultSearchResult result = second.search(VaultSearchQuery.of("jaki model czatu"), null, null);
        assertThat(result.semanticUsed()).isTrue();
        assertThat(result.hits().getFirst().path()).isEqualTo("knowledge/Ollama.md");
    }

    @Test
    void fallsBackToKeywordsWhenEmbeddingsFailAndRecoversLater() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/Głos.md", "# Głos\n\nSynteza mowy działa na serwerze Ubuntu przez Chatterbox.\n");
        TestEmbeddingBackends.Hashing backend = new TestEmbeddingBackends.Hashing(64);
        backend.failing.set(true);
        KnowledgeVaultService service = open(vault, backend, "m1");

        VaultSearchResult degraded = service.search(VaultSearchQuery.of("synteza mowy"), null, null);
        assertThat(degraded.hits()).isNotEmpty();
        assertThat(degraded.semanticUsed()).isFalse();
        assertThat(degraded.effectiveMode()).isEqualTo("TEXT");
        assertThat(degraded.semanticError()).contains("simulated provider outage");
        assertThat(service.status().pendingEmbeddings()).isEqualTo(service.status().chunks());
        assertThat((Boolean) service.status().embedding().get("available")).isFalse();

        backend.failing.set(false);
        assertThat(service.embedPendingNow()).isPositive();
        VaultSearchResult recovered = service.search(VaultSearchQuery.of("synteza mowy"), null, null);
        assertThat(recovered.semanticUsed()).isTrue();
        assertThat(service.status().pendingEmbeddings()).isZero();
    }

    @Test
    void neverMixesVectorsOfDifferentModelsOrDimensions() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/A.md", "# A\n\nPierwszy dokument o kopiach zapasowych.\n");
        KnowledgeVaultService first = vault.service(new TestEmbeddingBackends.Hashing(64), "model-a");
        first.startSynchronously();
        assertThat(first.status().embeddedChunks()).isEqualTo(1);
        first.close();

        // Another model with another dimension over the same index: old vectors are dropped and re-embedded.
        TestEmbeddingBackends.Hashing other = new TestEmbeddingBackends.Hashing(32);
        KnowledgeVaultService second = vault.service(other, "model-b");
        opened.add(second);
        second.startSynchronously();

        assertThat(other.inputs.get()).isEqualTo(1);
        assertThat(second.status().embedding().get("dimension")).isEqualTo(32);
        assertThat(second.search(VaultSearchQuery.of("kopie zapasowe"), null, null).semanticUsed()).isTrue();
    }

    @Test
    void doesNotIndexPromptCopiesSecretsOrObsidianInternals() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("knowledge/Serwer.md", "# Serwer\n\nKarta graficzna RTX 4060 Ti 16 GB.\n");
        vault.write("Archiwum/instrukcje-kopia.md", Files.readString(vault.promptFile));
        vault.write("jarvis.md", Files.readString(vault.promptFile));
        vault.write(".obsidian/workspace.json", "{\"main\": \"RESPONSE CONTRACT\"}");
        vault.write("knowledge/Dostępy.md", "# Dostępy\n\nhaslo: KorporacyjneHaslo123!\n");
        KnowledgeVaultService service = open(vault, null, "");

        assertThat(service.indexedDocument("jarvis.md")).isEmpty();
        assertThat(service.indexedDocument(".obsidian/workspace.json")).isEmpty();
        assertThat(service.indexedDocument("Archiwum/instrukcje-kopia.md").orElseThrow().state())
                .isEqualTo(StoredDocument.EXCLUDED_PROMPT);
        assertThat(service.indexedDocument("knowledge/Dostępy.md").orElseThrow().state())
                .isEqualTo(StoredDocument.EXCLUDED_SECRET);
        VaultSearchQuery all = new VaultSearchQuery("main response contract TOOL_REQUEST hasło", VaultSearchQuery.Mode.HYBRID,
                List.of(), "", List.of(), List.of(), "", true, false, 20, 0);
        assertThat(service.search(all, null, null).hits()).isEmpty();
        assertThatThrownBy(() -> service.read("knowledge/Dostępy.md", 1, null, null)).isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(() -> service.read("jarvis.md", 1, null, null)).isInstanceOf(KnowledgeException.class);
        assertThatThrownBy(() -> service.read("../config/jarvis.md", 1, null, null)).isInstanceOf(KnowledgeException.class);
    }

    @Test
    void filtersByTypeProjectTagsStatusAndHidesArchivedByDefault() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("projects/Nova.md", "---\ntype: project\nproject: Nova\ntags: [budżet]\nstatus: active\n---\n# Nova\n\nBudżet projektu Nova to 40 tysięcy.\n");
        vault.write("projects/Nova stara wersja.md", "---\ntype: project\nproject: Nova\nstatus: archived\n---\n# Nova (stare)\n\nBudżet projektu Nova to 25 tysięcy.\n");
        vault.write("decisions/Budżet.md", "---\ntype: decision\nproject: Nova\n---\n# Decyzja\n\nBudżet zatwierdzono w marcu.\n");
        KnowledgeVaultService service = open(vault, null, "");

        assertThat(service.search(VaultSearchQuery.of("budżet projektu Nova"), null, null).hits())
                .extracting(VaultSearchHit::path).doesNotContain("projects/Nova stara wersja.md");
        VaultSearchQuery archived = new VaultSearchQuery("budżet projektu Nova", VaultSearchQuery.Mode.TEXT, List.of(), "", List.of(),
                List.of(), "", true, false, 10, 0);
        assertThat(service.search(archived, null, null).hits()).extracting(VaultSearchHit::path).contains("projects/Nova stara wersja.md");
        VaultSearchQuery decisions = new VaultSearchQuery("budżet", VaultSearchQuery.Mode.TEXT, List.of("decision"), "Nova", List.of(),
                List.of(), "", false, false, 10, 0);
        assertThat(service.search(decisions, null, null).hits()).extracting(VaultSearchHit::path).containsExactly("decisions/Budżet.md");
        VaultSearchQuery tagged = new VaultSearchQuery("budżet", VaultSearchQuery.Mode.TEXT, List.of(), "", List.of("budżet"),
                List.of(), "", false, false, 10, 0);
        assertThat(service.search(tagged, null, null).hits()).extracting(VaultSearchHit::path).containsExactly("projects/Nova.md");
    }

    @Test
    void readsACompleteWorkflowAndPagesALongOneExplicitly() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("workflows/Krótki.md", "---\ntype: workflow\n---\n# Restart usług\n\n1. Zatrzymaj Jarvis.\n2. Uruchom Ollama.\n3. Uruchom Jarvis.\n");
        StringBuilder steps = new StringBuilder("---\ntype: workflow\nid: wf-dlugi\n---\n# Długa procedura wdrożenia\n\n");
        for (int section = 1; section <= 30; section++) {
            steps.append("## Etap ").append(section).append("\n\n");
            for (int step = 1; step <= 6; step++) {
                steps.append(step).append(". Wykonaj czynność ").append(section).append('.').append(step)
                        .append(" i sprawdź wynik w logach usługi przed przejściem dalej.\n");
            }
            steps.append('\n');
        }
        steps.append("## Etap końcowy\n\nOstatnia zasada: nigdy nie wdrażaj w piątek po 15:00.\n");
        vault.write("workflows/Wdrożenie.md", steps.toString());
        vault.write("knowledge/Fakty.md", "# Fakty\n\nZwykła wiedza, nie procedura.\n");
        KnowledgeVaultService service = vault.service(null, "", 512, 1500);
        opened.add(service);
        service.startSynchronously();

        List<WorkflowCandidate> candidates = service.findWorkflows("wdrożenie", false);
        assertThat(candidates).extracting(WorkflowCandidate::path).first().isEqualTo("workflows/Wdrożenie.md");
        assertThat(candidates.getFirst().estimatedParts()).isGreaterThan(1);

        PagedDocument shortOne = service.readWorkflow("workflows/Krótki.md", 1, "c", "r");
        assertThat(shortOne.complete()).isTrue();
        assertThat(shortOne.content()).contains("3. Uruchom Jarvis.");

        PagedDocument first = service.readWorkflow("workflows/Wdrożenie.md", 1, "c", "r");
        assertThat(first.complete()).isFalse();
        assertThat(first.parts()).isGreaterThan(1);
        assertThat(first.notes()).anyMatch(note -> note.startsWith("PART 1 OF " + first.parts()));
        assertThat(first.notes()).anyMatch(note -> note.contains("Do not start executing"));
        StringBuilder reassembled = new StringBuilder(first.content());
        for (int part = 2; part <= first.parts(); part++) {
            PagedDocument next = service.readWorkflow("workflows/Wdrożenie.md", part, "c", "r");
            assertThat(next.startLine()).isEqualTo(service.readWorkflow("workflows/Wdrożenie.md", part - 1, null, null).endLine() + 1);
            reassembled.append('\n').append(next.content());
        }
        // Concatenating every part reproduces the file exactly: nothing was dropped or truncated.
        assertThat(reassembled.toString()).isEqualTo(Files.readString(vault.root.resolve("workflows/Wdrożenie.md")));
        assertThat(first.outline()).anySatisfy(entry -> {
            assertThat(entry.heading()).isEqualTo("Etap końcowy");
            assertThat(entry.part()).isEqualTo(first.parts());
        });
        assertThatThrownBy(() -> service.readWorkflow("knowledge/Fakty.md", 1, null, null))
                .isInstanceOf(KnowledgeException.class).hasMessageContaining("Not a workflow");
    }

    @Test
    void existingWorkflowNamingIsRecognizedWithoutFrontmatter() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("Work/Scheduling/StoreAuditScheduleWorkflow.md", "# PROCEDURA PLANOWANIA GRAFIKU AUDYTÓW SKLEPÓW\n\n## 1. CEL\n\nGrafik audytów.\n");
        KnowledgeVaultService service = open(vault, null, "");

        assertThat(service.findWorkflows("grafik audytów sklepów", false)).extracting(WorkflowCandidate::path)
                .containsExactly("Work/Scheduling/StoreAuditScheduleWorkflow.md");
    }

    @Test
    void reSplitsAFragmentTheProviderRejectsAsTooLongInsteadOfTruncating() throws IOException {
        TestVault vault = new TestVault(temp);
        StringBuilder paragraph = new StringBuilder("# Notatka\n\n");
        for (int index = 0; index < 40; index++) {
            paragraph.append("Zdanie ").append(index).append(" o konfiguracji kopii zapasowych. ");
        }
        vault.write("knowledge/Długi akapit.md", paragraph.toString());
        // The provider accepts at most 700 characters although the configured token limit would allow more.
        TestEmbeddingBackends.Hashing strict = new TestEmbeddingBackends.Hashing(64, 700);
        KnowledgeVaultService service = vault.service(strict, "m1", 512, 0);
        opened.add(service);
        service.startSynchronously();
        for (int round = 0; round < 5 && service.status().pendingEmbeddings() > 0; round++) {
            service.reindex();
            service.embedPendingNow();
        }

        assertThat(service.status().pendingEmbeddings()).isZero();
        assertThat(service.status().chunks()).isGreaterThan(1);
    }
}
