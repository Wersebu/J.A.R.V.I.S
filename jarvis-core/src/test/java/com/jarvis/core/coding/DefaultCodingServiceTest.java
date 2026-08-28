package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultCodingServiceTest {

    @TempDir
    private Path tempDir;

    @Test
    void blocksPathTraversalReadsAndWritesOutsideWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("secret.txt"), "outside", StandardCharsets.UTF_8);
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspaceRoot.resolve("inside.txt"), "inside", StandardCharsets.UTF_8);

        DefaultCodingService service = new DefaultCodingService();
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.AUTONOMOUS_IN_WORKSPACE);

        assertThat(service.readFile(workspace.id(), "inside.txt", null, null).content()).isEqualTo("inside");
        assertThatThrownBy(() -> service.readFile(workspace.id(), "..\\secret.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
        assertThatThrownBy(() -> service.writeFile(workspace.id(), new CodingService.FileWriteRequest("..\\created.txt", "nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
        assertThat(tempDir.resolve("created.txt")).doesNotExist();
    }

    @Test
    void rejectsPatchWhenExpectedContentChanged() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspaceRoot.resolve("App.java"), "class App { int value = 1; }", StandardCharsets.UTF_8);

        DefaultCodingService service = new DefaultCodingService();
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.AUTONOMOUS_IN_WORKSPACE);

        var patched = service.patchFile(workspace.id(), new CodingService.PatchRequest("App.java", "value = 1", "value = 2"));
        assertThat(patched.content()).contains("value = 2");
        assertThatThrownBy(() -> service.patchFile(workspace.id(), new CodingService.PatchRequest("App.java", "value = 1", "value = 3")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Patch conflict");
    }

    @Test
    void detectsMavenWrapperAndRunsCommandWithOutputLimit() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspaceRoot.resolve("mvnw.cmd"), "@echo off", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("pom.xml"), "<project />", StandardCharsets.UTF_8);
        Files.writeString(workspaceRoot.resolve("needle.txt"), "first\nneedle here\nlast\n", StandardCharsets.UTF_8);

        DefaultCodingService service = new DefaultCodingService();
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.AUTONOMOUS_IN_WORKSPACE);

        assertThat(workspace.detectedBuildSystems()).contains("Maven");
        assertThat(workspace.buildCommand()).startsWith(".\\mvnw.cmd");
        assertThat(service.search(workspace.id(), new CodingService.FileSearchRequest("needle", false, 10)))
                .extracting(CodingService.SearchMatch::line)
                .containsExactly(2);

        var result = service.runCommand(workspace.id(), new CodingService.CommandRequest("echo 1234567890", 5, 5));
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).hasSizeLessThanOrEqualTo(7);
    }

    @Test
    void blocksCommandsInReadOnlyAndDestructiveGitOperations() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        DefaultCodingService service = new DefaultCodingService();
        var readOnly = register(service, workspaceRoot, CodingService.AutonomyLevel.READ_ONLY);

        assertThatThrownBy(() -> service.runCommand(readOnly.id(), new CodingService.CommandRequest("echo safe", 5, 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("READ_ONLY");

        var autonomous = register(service, workspaceRoot, CodingService.AutonomyLevel.AUTONOMOUS_IN_WORKSPACE);
        assertThatThrownBy(() -> service.runCommand(autonomous.id(), new CodingService.CommandRequest("git reset --hard", 5, 100)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit approval");
    }

    @Test
    void returnsGitStatusAndDiffWithoutCreatingCommit() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repo"));
        run(workspaceRoot, "git", "init");
        run(workspaceRoot, "git", "config", "user.email", "test@example.com");
        run(workspaceRoot, "git", "config", "user.name", "Test User");
        Files.writeString(workspaceRoot.resolve("README.md"), "old\n", StandardCharsets.UTF_8);
        run(workspaceRoot, "git", "add", "README.md");
        run(workspaceRoot, "git", "commit", "-m", "initial");
        Files.writeString(workspaceRoot.resolve("README.md"), "new\n", StandardCharsets.UTF_8);

        DefaultCodingService service = new DefaultCodingService();
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.ASK_BEFORE_WRITE);
        var git = service.gitSnapshot(workspace.id());

        assertThat(git.branch()).isNotBlank();
        assertThat(git.status()).contains("README.md");
        assertThat(git.diff()).contains("-old").contains("+new");
    }

    private CodingService.CodingWorkspace register(DefaultCodingService service, Path root, CodingService.AutonomyLevel autonomy) {
        return service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "project",
                root.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                autonomy,
                "",
                ""
        ));
    }

    private void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        assertThat(process.waitFor()).isZero();
    }
}
