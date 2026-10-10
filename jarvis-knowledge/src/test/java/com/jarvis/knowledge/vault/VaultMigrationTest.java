package com.jarvis.knowledge.vault;

import com.jarvis.knowledge.vault.migration.VaultMigration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class VaultMigrationTest {

    @TempDir
    Path temp;

    private static String procedure() {
        StringBuilder text = new StringBuilder("# PROCEDURA PLANOWANIA GRAFIKU AUDYTÓW SKLEPÓW\n\n");
        for (int index = 1; index <= 30; index++) {
            text.append("## ").append(index).append(". ETAP\n\nKażdy sklep należy traktować jako osobną lokalizację, krok ")
                    .append(index).append(".\n\n");
        }
        return text.toString();
    }

    @Test
    void dryRunChangesNothingAndApplyIsBackedUpContentPreservingAndIdempotent() throws IOException {
        TestVault vault = new TestVault(temp);
        String full = procedure();
        vault.write("Work/Scheduling/StoreAuditScheduleWorkflow.md", full);
        vault.write("GraphicDesignWorkflow.md", full.substring(0, full.length() / 2));
        vault.write("Java/Spring.md", "# Spring\n\nNotatki o Spring Boot.\n");
        vault.write("Java/Spring kopia.md", "---\nid: kopia-1\n---\n# Spring\n\nNotatki o Spring Boot.\n");
        String historyId = "11111111-2222-3333-4444-555555555555";
        vault.write(".history/" + historyId + "/2026-01-01T00-00-00Z.meta", "versionId=2026-01-01T00-00-00Z\nrelativePath=Java/Spring.md\ntimestamp=2026-01-01T00:00:00Z\n");
        VaultMigration migration = new VaultMigration(vault.policy(), ".history");
        VaultMigration.Plan plan = new VaultMigration.Plan(List.of(),
                Map.of("Work/Scheduling/StoreAuditScheduleWorkflow.md", Map.of("type", "workflow")), List.of());
        Map<Path, String> before = snapshot(vault.root);

        VaultMigration.Report dryRun = migration.plan(plan, true);

        assertThat(snapshot(vault.root)).isEqualTo(before);
        assertThat(dryRun.dryRun()).isTrue();
        assertThat(dryRun.items()).filteredOn(item -> item.source().equals("Java/Spring.md")).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(historyId);
                    assertThat(item.idSource()).isEqualTo("history");
                });
        assertThat(dryRun.findings()).anySatisfy(finding -> {
            assertThat(finding.kind()).isEqualTo("TRUNCATED_COPY");
            assertThat(finding.path()).isEqualTo("GraphicDesignWorkflow.md");
            assertThat(finding.related()).isEqualTo("Work/Scheduling/StoreAuditScheduleWorkflow.md");
        });
        assertThat(dryRun.findings()).anySatisfy(finding -> {
            assertThat(finding.kind()).isEqualTo("NAME_MISMATCH");
            assertThat(finding.path()).isEqualTo("GraphicDesignWorkflow.md");
        });
        assertThat(dryRun.findings()).anySatisfy(finding -> {
            assertThat(finding.kind()).isEqualTo("DUPLICATE");
            assertThat(finding.related()).isIn("Java/Spring.md", "Java/Spring kopia.md");
        });
        assertThat(dryRun.foldersToCreate()).containsExactlyElementsOf(VaultMigration.SUGGESTED_FOLDERS);

        VaultMigration.Report applied = migration.apply(plan, true, temp.resolve("backups"));

        assertThat(applied.backup()).isNotBlank();
        assertThat(Files.readString(Path.of(applied.backup()).resolve("Java/Spring.md"))).isEqualTo("# Spring\n\nNotatki o Spring Boot.\n");
        assertThat(Files.readString(vault.root.resolve("Java/Spring.md")))
                .isEqualTo("---\nid: " + historyId + "\n---\n# Spring\n\nNotatki o Spring Boot.\n");
        assertThat(Files.readString(vault.root.resolve("Java/Spring kopia.md"))).startsWith("---\nid: kopia-1\n---");
        assertThat(Files.readString(vault.root.resolve("Work/Scheduling/StoreAuditScheduleWorkflow.md")))
                .contains("type: workflow").endsWith(full);
        assertThat(Files.exists(vault.root.resolve("GraphicDesignWorkflow.md"))).isTrue();
        assertThat(Files.isDirectory(vault.root.resolve("workflows"))).isTrue();

        Map<Path, String> afterFirstApply = snapshot(vault.root);
        VaultMigration.Report again = migration.apply(plan, true, temp.resolve("backups"));
        assertThat(again.backup()).isEmpty();
        assertThat(again.items()).allSatisfy(item -> assertThat(item.actions()).containsExactly("NONE"));
        assertThat(snapshot(vault.root)).isEqualTo(afterFirstApply);
    }

    @Test
    void plannedMovesNeverOverwriteAndFrontmatterEditsKeepOtherLines() throws IOException {
        TestVault vault = new TestVault(temp);
        vault.write("a.md", "---\ntitle: A\ntags: [x]\n---\nTreść A\n");
        vault.write("b.md", "Treść B\n");
        VaultMigration migration = new VaultMigration(vault.policy(), ".history");
        VaultMigration.Plan plan = new VaultMigration.Plan(List.of(new VaultMigration.Move("a.md", "b.md"),
                new VaultMigration.Move("b.md", "knowledge/b.md")), Map.of(), List.of("knowledge"));

        VaultMigration.Report report = migration.apply(plan, true, temp.resolve("backups"));

        assertThat(report.findings()).anySatisfy(finding -> assertThat(finding.kind()).isEqualTo("CONFLICT"));
        assertThat(Files.readString(vault.root.resolve("a.md"))).startsWith("---\ntitle: A\ntags: [x]\nid: ").endsWith("---\nTreść A\n");
        assertThat(Files.readString(vault.root.resolve("knowledge/b.md"))).endsWith("Treść B\n");
        assertThat(VaultMigration.setFrontmatter("---\nid: x\n---\nT", "id", "x")).isEqualTo("---\nid: x\n---\nT");
    }

    private static Map<Path, String> snapshot(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toMap(root::relativize, file -> {
                try {
                    return Files.readString(file);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }));
        }
    }
}
