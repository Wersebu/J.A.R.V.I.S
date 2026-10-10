package com.jarvis.knowledge.vault.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jarvis.knowledge.vault.PromptFileGuard;
import com.jarvis.knowledge.vault.VaultPathPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Command line entry point of the vault migration. Dry-run unless {@code --apply} is given.
 *
 * <pre>
 * java -cp jarvis-core.jar -Dloader.main=com.jarvis.knowledge.vault.migration.VaultMigrationCli \
 *      org.springframework.boot.loader.launch.PropertiesLauncher \
 *      --vault ./knowledge [--plan config/vault-migration.yml] [--prompt config/jarvis.md] [--no-ids] \
 *      [--report-dir ./data/vault-migration] [--backup-dir ./backups] [--apply]
 * </pre>
 */
public final class VaultMigrationCli {

    private VaultMigrationCli() {
    }

    /**
     * Runs the migration.
     *
     * @param args arguments
     * @throws Exception on failure
     */
    public static void main(String[] args) throws Exception {
        Path vault = Path.of("./knowledge");
        Path plan = null;
        List<Path> prompts = new ArrayList<>(List.of(Path.of("config/jarvis.md")));
        Path reportDir = Path.of("./data/vault-migration");
        Path backupDir = Path.of("./backups");
        boolean apply = false;
        boolean addIds = true;
        for (int index = 0; index < args.length; index++) {
            switch (args[index]) {
                case "--vault" -> vault = Path.of(args[++index]);
                case "--plan" -> plan = Path.of(args[++index]);
                case "--prompt" -> prompts.add(Path.of(args[++index]));
                case "--report-dir" -> reportDir = Path.of(args[++index]);
                case "--backup-dir" -> backupDir = Path.of(args[++index]);
                case "--apply" -> apply = true;
                case "--no-ids" -> addIds = false;
                case "--help", "-h" -> {
                    System.out.println("Usage: --vault DIR [--plan FILE.yml] [--prompt FILE] [--no-ids] [--report-dir DIR] [--backup-dir DIR] [--apply]");
                    return;
                }
                default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
            }
        }
        if (!Files.isDirectory(vault)) {
            throw new IllegalArgumentException("Vault folder does not exist: " + vault.toAbsolutePath());
        }
        VaultPathPolicy policy = new VaultPathPolicy(vault, true, List.of(), new PromptFileGuard(prompts));
        VaultMigration migration = new VaultMigration(policy, ".history");
        VaultMigration.Plan loaded = VaultMigration.loadPlan(plan);
        VaultMigration.Report report = apply ? migration.apply(loaded, addIds, backupDir.toAbsolutePath()) : migration.plan(loaded, addIds);

        Path out = reportDir.resolve(Instant.now().toString().replace(':', '-') + (report.dryRun() ? "-dry-run" : "-applied"));
        Files.createDirectories(out);
        Files.writeString(out.resolve("migration-report.md"), report.toMarkdown(), StandardCharsets.UTF_8);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(out.resolve("migration-report.json").toFile(), report);
        System.out.println(report.toMarkdown());
        System.out.println("Report written to " + out.toAbsolutePath());
        if (report.dryRun()) {
            System.out.println("DRY-RUN: nothing was changed. Re-run with --apply to perform it (a backup is created first).");
        }
    }
}
