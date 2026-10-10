package com.jarvis.knowledge.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VaultPathPolicyTest {

    @TempDir
    Path temp;

    @Test
    void rejectsPathsLeavingTheVault() throws IOException {
        VaultPathPolicy policy = new TestVault(temp).policy();

        for (String path : new String[]{"../secret.md", "notes/../../x.md", "/etc/passwd", "C:\\Windows\\win.ini", "C:x.md",
                "\\\\server\\share\\a.md", "~/notes.md", "a\0b.md"}) {
            assertThatThrownBy(() -> policy.resolve(path))
                    .as(path)
                    .isInstanceOf(VaultPathPolicy.VaultPathException.class);
        }
        assertThat(policy.resolve("projects/Serwer domowy – notatki.md"))
                .isEqualTo(policy.root().resolve("projects").resolve("Serwer domowy – notatki.md"));
        assertThat(policy.resolve("./projects//a.md")).isEqualTo(policy.root().resolve("projects").resolve("a.md"));
    }

    @Test
    void rejectsSymbolicLinksPointingOutsideTheVault() throws IOException {
        TestVault vault = new TestVault(temp);
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("private.md"), "prywatne");
        Path link = vault.root.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException exception) {
            // Windows without the symlink privilege: a directory junction needs no privilege and escapes the same way.
            boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            assumeTrue(windows, "symbolic links not permitted on this machine: " + exception.getMessage());
            try {
                Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), outside.toString())
                        .redirectErrorStream(true).start();
                assumeTrue(process.waitFor() == 0 && Files.exists(link), "junction could not be created");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                assumeTrue(false, "interrupted");
            }
        }
        VaultPathPolicy policy = vault.policy();
        try {
            assertThatThrownBy(() -> policy.resolve("link/private.md")).isInstanceOf(VaultPathPolicy.VaultPathException.class);
            assertThat(policy.isSafeRegularFile(link.resolve("private.md"))).isFalse();
        } finally {
            // Remove only the link itself (for a junction, rmdir never touches the target).
            if (!Files.deleteIfExists(link) && Files.exists(link)) {
                try {
                    new ProcessBuilder("cmd.exe", "/c", "rmdir", link.toString()).start().waitFor();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @Test
    void excludesInternalsTemporaryFilesBackupsSecretsAndPromptCopies() throws IOException {
        VaultPathPolicy policy = new TestVault(temp).policy();

        for (String excluded : new String[]{".obsidian/workspace.json", ".obsidian/plugins/x/main.js", ".git/config", "sub/.git/HEAD",
                ".trash/stare.md", ".history/abc/1.meta", ".drafts/d.draft", "notes/szkic.md.tmp", "notes/plik.md~", "notes/plik.md.bak",
                "notes/.~lock.plik.md#", ".env", "config/.env.local", "klucze/server.pem", "hasla/kdbx.kdbx", "moje-secrets.md",
                "api-credentials.md", "jarvis.md", "kopie/jarvis (1).md", "jarvis-backup.md", "JARVIS.md",
                "notes/Plik.sync-conflict-20260101-120000.md"}) {
            assertThat(policy.isExcluded(excluded)).as(excluded).isTrue();
        }
        for (String included : new String[]{"projects/Jarvis.md", "knowledge/Jarvis – architektura.md", "workflows/Audyt sklepów.md",
                "attachments/schemat.png", "preferences/Styl odpowiedzi.md"}) {
            assertThat(policy.isExcluded(included)).as(included).isFalse();
        }
    }

    @Test
    void detectsPromptCopiesByContentEvenUnderAnotherName() throws IOException {
        TestVault vault = new TestVault(temp);
        PromptFileGuard guard = new PromptFileGuard(java.util.List.of(vault.promptFile));
        String prompt = Files.readString(vault.promptFile);

        assertThat(guard.isPromptContent(prompt)).isTrue();
        assertThat(guard.isPromptContent(prompt.replace("\n", "\r\n") + "\n\n")).isTrue();
        assertThat(guard.isPromptContent("---\ntype: system-prompt\n---\nCokolwiek")).isTrue();
        assertThat(guard.isPromptContent("# Serwer\nKarta graficzna to RTX 4060 Ti 16 GB współdzielona z modelem.\n")).isFalse();
    }

    @Test
    void secretScannerFlagsCredentialsButNotPlaceholders() {
        assertThat(SecretScanner.detect("token: ghp_abcdefghijklmnopqrstuvwxyz0123456789AB")).isPresent();
        assertThat(SecretScanner.detect("-----BEGIN OPENSSH PRIVATE KEY-----\nabc")).isPresent();
        assertThat(SecretScanner.detect("password = SuperTajneHaslo2026!")).isPresent();
        assertThat(SecretScanner.detect("api-key: ${TOPKIMC_MODERATION_API_KEY}")).isEmpty();
        assertThat(SecretScanner.detect("Hasło do Wi-Fi zmieniamy co kwartał, procedura w dziale IT.")).isEmpty();
    }
}
