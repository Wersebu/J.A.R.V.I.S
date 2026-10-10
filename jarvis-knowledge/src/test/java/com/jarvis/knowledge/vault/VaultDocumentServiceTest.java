package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.DefaultKnowledgeService;
import com.jarvis.knowledge.InMemoryKnowledgeIndex;
import com.jarvis.knowledge.KnowledgeProperties;
import com.jarvis.knowledge.LoggingKnowledgeEventPublisher;
import com.jarvis.knowledge.Sha256Hasher;
import com.jarvis.knowledge.SupportedFileTypes;
import com.jarvis.knowledge.extract.DocumentExtractorRegistry;
import com.jarvis.knowledge.extract.MarkdownExtractor;
import com.jarvis.knowledge.extract.TextExtractor;
import com.jarvis.knowledge.vault.edit.VaultDocumentService;
import com.jarvis.knowledge.vault.edit.VaultDocumentView;
import com.jarvis.knowledge.vault.edit.VaultEditException;
import com.jarvis.knowledge.vault.edit.VaultTreeNode;
import com.jarvis.knowledge.vault.edit.VaultWriteResult;
import com.jarvis.knowledge.workspace.KnowledgeVersion;
import com.jarvis.knowledge.workspace.KnowledgeWorkspaceProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultDocumentServiceTest {

    @TempDir
    Path temp;

    private TestVault vault;
    private KnowledgeVaultService vaultService;
    private VaultDocumentService documents;

    @BeforeEach
    void setUp() throws IOException {
        vault = new TestVault(temp);
        vault.write("projects/Serwer domowy.md", "---\nid: proj-serwer\ntype: project\n---\n# Serwer domowy\n\nRTX 4060 Ti 16 GB. Zobacz [[Sieć]].\n");
        vault.write("knowledge/Sieć.md", "# Sieć\n\nRouter w piwnicy.\n");
        vault.write("attachments/schemat.png", "png");
        vault.write(".obsidian/app.json", "{}");
        vaultService = vault.service(null, "");
        vaultService.startSynchronously();
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        InMemoryKnowledgeIndex legacyIndex = new InMemoryKnowledgeIndex();
        DefaultKnowledgeService legacy = new DefaultKnowledgeService(new KnowledgeProperties(vault.root.toString(), false, 500, 300),
                legacyIndex, new SupportedFileTypes(), new DocumentExtractorRegistry(List.of(new MarkdownExtractor(), new TextExtractor())),
                new Sha256Hasher(), new LoggingKnowledgeEventPublisher());
        legacy.reindex();
        documents = new VaultDocumentService(vault.policy(), legacy, legacyIndex, new KnowledgeWorkspaceProperties(null, null, null),
                vaultService, beans.getBeanProvider(com.jarvis.knowledge.workspace.KnowledgeWorkspaceService.class));
    }

    @AfterEach
    void tearDown() {
        vaultService.close();
    }

    @Test
    void treeShowsNotesFoldersAndAttachmentsButHidesObsidianInternals() {
        VaultTreeNode root = documents.tree();

        assertThat(root.children()).extracting(VaultTreeNode::name).containsExactly("attachments", "knowledge", "projects");
        VaultTreeNode project = root.children().get(2).children().getFirst();
        assertThat(project.kind()).isEqualTo("note");
        assertThat(project.documentId()).isEqualTo("proj-serwer");
        assertThat(project.indexState()).isEqualTo("INDEXED");
        assertThat(root.children().getFirst().children().getFirst().kind()).isEqualTo("attachment");
    }

    @Test
    void readReturnsMetadataLinksBacklinksAndVersion() {
        VaultDocumentView view = documents.read("projects/Serwer domowy.md");

        assertThat(view.frontmatterId()).isEqualTo("proj-serwer");
        assertThat(view.type()).isEqualTo("project");
        assertThat(view.version()).hasSize(64);
        assertThat(view.links()).singleElement().satisfies(link -> {
            assertThat(link.resolvedPath()).isEqualTo("knowledge/Sieć.md");
            assertThat(link.exists()).isTrue();
        });
        assertThat(documents.read("knowledge/Sieć.md").backlinks()).containsExactly("projects/Serwer domowy.md");
    }

    @Test
    void savingWithAStaleVersionIsRejectedAndNeverOverwritesAParallelEdit() throws IOException {
        VaultDocumentView opened = documents.read("knowledge/Sieć.md");
        // Meanwhile the note is edited in Obsidian.
        vault.write("knowledge/Sieć.md", "# Sieć\n\nRouter przeniesiony na strych (edycja w Obsidianie).\n");

        assertThatThrownBy(() -> documents.save("knowledge/Sieć.md", "# Sieć\n\nMoja zmiana z Jarvisa.\n", opened.version()))
                .isInstanceOfSatisfying(VaultEditException.class, conflict -> {
                    assertThat(conflict.reason()).isEqualTo(VaultEditException.Reason.CONFLICT);
                    assertThat((String) conflict.details().get("currentContent")).contains("edycja w Obsidianie");
                });
        assertThat(Files.readString(vault.root.resolve("knowledge/Sieć.md"))).contains("edycja w Obsidianie");

        VaultDocumentView fresh = documents.read("knowledge/Sieć.md");
        VaultWriteResult saved = documents.save("knowledge/Sieć.md", "# Sieć\n\nScalona wersja.\n", fresh.version());
        assertThat(saved.version()).isNotEqualTo(fresh.version());
        assertThat(Files.readString(vault.root.resolve("knowledge/Sieć.md"))).isEqualTo("# Sieć\n\nScalona wersja.\n");
        assertThatThrownBy(() -> documents.save("knowledge/Sieć.md", "x", fresh.version()))
                .isInstanceOfSatisfying(VaultEditException.class,
                        conflict -> assertThat(conflict.reason()).isEqualTo(VaultEditException.Reason.CONFLICT));
    }

    @Test
    void historyKeepsPreviousVersionsAndRestoreUsesTheSameMechanism() {
        VaultDocumentView first = documents.read("knowledge/Sieć.md");
        VaultWriteResult second = documents.save("knowledge/Sieć.md", "# Sieć\n\nWersja druga.\n", first.version());

        List<KnowledgeVersion> history = documents.history("knowledge/Sieć.md");
        assertThat(history).singleElement().satisfies(version -> assertThat(version.author()).isEqualTo("USER"));
        assertThat(documents.versionContent("knowledge/Sieć.md", history.getFirst().versionId())).contains("Router w piwnicy");

        VaultWriteResult restored = documents.restore("knowledge/Sieć.md", history.getFirst().versionId(), second.version());
        assertThat(documents.read("knowledge/Sieć.md").content()).contains("Router w piwnicy");
        assertThat(documents.history("knowledge/Sieć.md")).hasSize(2);
        assertThat(restored.savedVersionId()).isNotBlank();
    }

    @Test
    void createsRenamesAndMovesWithoutReplacingExistingTargets() {
        assertThat(documents.create("decisions/Wybór bazy", "").path()).isEqualTo("decisions/Wybór bazy.md");
        assertThatThrownBy(() -> documents.create("decisions/Wybór bazy.md", "x"))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.ALREADY_EXISTS));
        documents.createFolder("archiwum");

        assertThatThrownBy(() -> documents.move("knowledge/Sieć.md", "projects/Serwer domowy.md", null))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.ALREADY_EXISTS));
        assertThat(Files.exists(vault.root.resolve("projects/Serwer domowy.md"))).isTrue();

        documents.move("knowledge/Sieć.md", "knowledge/Sieć domowa.md", null);
        documents.move("knowledge", "archiwum/knowledge", null);
        assertThat(Files.exists(vault.root.resolve("archiwum/knowledge/Sieć domowa.md"))).isTrue();
        assertThatThrownBy(() -> documents.move("archiwum", "archiwum/w środku", null))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.INVALID));
    }

    @Test
    void rejectsPathsOutsideTheVaultAndExcludedLocations() {
        assertThatThrownBy(() -> documents.read("../config/jarvis.md"))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.INVALID));
        assertThatThrownBy(() -> documents.create("../poza.md", "x"))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.INVALID));
        assertThatThrownBy(() -> documents.read(".obsidian/app.json"))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.EXCLUDED));
        assertThatThrownBy(() -> documents.move("knowledge/Sieć.md", "../../ucieczka.md", null))
                .isInstanceOfSatisfying(VaultEditException.class, error -> assertThat(error.reason()).isEqualTo(VaultEditException.Reason.INVALID));
        assertThatThrownBy(() -> documents.create("notatki/szkic.md.bak", "x"))
                .isInstanceOf(VaultEditException.class);
        assertThat(Files.exists(temp.resolve("poza.md"))).isFalse();
    }
}
