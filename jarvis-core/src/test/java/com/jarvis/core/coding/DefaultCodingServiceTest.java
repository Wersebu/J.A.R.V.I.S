package com.jarvis.core.coding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.api.service.CodingService;
import com.jarvis.common.ai.AIJobType;
import com.jarvis.common.ai.AIProvider;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ModelMessage;
import com.jarvis.common.ai.ModelResponse;
import com.jarvis.common.ai.ModelToolCall;
import com.jarvis.common.ai.ModelUsage;
import com.jarvis.common.ai.NativeToolDefinition;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.common.dto.ChatResponse;
import com.jarvis.common.event.ChatEventSink;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.tools.JarvisTool;
import com.jarvis.tools.ToolManager;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.ToolRuntimeProperties;
import com.jarvis.tools.dataset.StoreAuditDatasetService;
import com.jarvis.tools.runtime.NativeToolLoopService;
import com.jarvis.tools.runtime.NativeToolSchemaMapper;
import com.jarvis.tools.runtime.ToolCallingRuntime;
import com.jarvis.tools.runtime.ToolIntent;
import com.jarvis.tools.runtime.ToolLoopTerminationInfo;
import com.jarvis.tools.runtime.ToolLoopTerminationReason;
import com.jarvis.tools.runtime.ToolRuntimeDebugService;
import com.jarvis.tools.schema.ToolDefinition;
import com.jarvis.tools.schema.ToolRegistry;
import com.jarvis.memory.cognitive.MemoryProperties;
import com.jarvis.memory.sqlite.SQLiteConnectionFactory;
import com.jarvis.memory.sqlite.SQLiteMemoryInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultCodingServiceTest {

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setAuthenticatedUser() {
        CurrentUserContext.set("test-user");
    }

    @AfterEach
    void clearAuthenticatedUser() {
        CurrentUserContext.clear();
    }

    @Test
    void blocksPathTraversalReadsAndWritesOutsideWorkspace() throws Exception {
        Files.writeString(tempDir.resolve("secret.txt"), "outside", StandardCharsets.UTF_8);
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspaceRoot.resolve("inside.txt"), "inside", StandardCharsets.UTF_8);

        DefaultCodingService service = synchronousService(null);
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
    void productionCodingPathRequiresAuthenticatedUserForWorkspaceAndTaskCreation() throws Exception {
        CurrentUserContext.clear();
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("auth-required-project"));
        DefaultCodingService service = synchronousService(null);

        assertThatThrownBy(() -> service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "project",
                workspaceRoot.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.EDIT_AND_TEST,
                "",
                ""
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authenticated user context is required");

        assertThatThrownBy(() -> service.startTask(new CodingService.StartTaskRequest("workspace-1", "conv-1", "fake", "run tests")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authenticated user context is required");
    }

    @Test
    void rejectsPatchWhenExpectedContentChanged() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspaceRoot.resolve("App.java"), "class App { int value = 1; }", StandardCharsets.UTF_8);

        DefaultCodingService service = synchronousService(null);
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

        DefaultCodingService service = synchronousService(null);
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
        DefaultCodingService service = synchronousService(null);
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

        DefaultCodingService service = synchronousService(null);
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.ASK_BEFORE_WRITE);
        var git = service.gitSnapshot(workspace.id());

        assertThat(git.branch()).isNotBlank();
        assertThat(git.status()).contains("README.md");
        assertThat(git.diff()).contains("-old").contains("+new");
    }

    @Test
    void startTaskRunsStatefulVerificationLoopAndCapturesReport() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(workspaceRoot.resolve("README.md"), "demo project\n", StandardCharsets.UTF_8);

        DefaultCodingService service = synchronousService(null);
        var workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "project",
                workspaceRoot.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.EDIT_AND_TEST,
                command("echo build-ok"),
                command("echo test-ok")
        ));

        service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "fake", "run tests"));
        var task = service.tasks().getFirst();

        assertThat(task.status()).isEqualTo(CodingService.CodingTaskStatus.COMPLETED);
        assertThat(task.iteration()).isEqualTo(1);
        assertThat(task.plan()).extracting(CodingService.PlanStep::status)
                .contains(CodingService.PlanStepStatus.COMPLETED);
        assertThat(task.changedFiles()).containsEntry("instructions", "README.md");
        assertThat(task.testResult()).contains("exitCode=0").contains("test-ok");
        assertThat(task.failureReason()).isBlank();
    }

    @Test
    void startTaskReturnsFailureWhenVerificationCommandFails() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));

        DefaultCodingService service = synchronousService(null);
        var workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "project",
                workspaceRoot.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.EDIT_AND_TEST,
                command("echo build-ok"),
                failingCommand()
        ));

        service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "fake", "run tests"));
        var task = service.tasks().getFirst();

        assertThat(task.status()).isEqualTo(CodingService.CodingTaskStatus.FAILED);
        assertThat(task.testResult()).contains("exitCode=");
        assertThat(task.failureReason()).contains("Verification command failed");
    }

    @Test
    void startTaskStopsForApprovalBeforeCommandsInReadOnlyMode() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));

        DefaultCodingService service = synchronousService(null);
        var workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "project",
                workspaceRoot.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.READ_ONLY,
                command("echo build-ok"),
                command("echo test-ok")
        ));

        service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "fake", "run tests"));
        var task = service.tasks().getFirst();

        assertThat(task.status()).isEqualTo(CodingService.CodingTaskStatus.WAITING_FOR_APPROVAL);
        assertThat(task.testResult()).isBlank();
        assertThat(task.failureReason()).contains("requires EDIT_AND_TEST");
    }

    @Test
    void startTaskReturnsQueuedBeforeBackgroundLoopRuns() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("project"));
        Queue<Runnable> queued = new ArrayDeque<>();
        Executor capturingExecutor = queued::add;
        DefaultCodingService service = new DefaultCodingService(null, new InMemoryCodingTaskRepository(), null, null, null, capturingExecutor);
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST);

        var task = service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "fake", "run tests"));

        assertThat(task.status()).isEqualTo(CodingService.CodingTaskStatus.QUEUED);
        assertThat(service.task(task.id()).status()).isEqualTo(CodingService.CodingTaskStatus.QUEUED);
        assertThat(queued).hasSize(1);
    }

    @Test
    void codingWorkspaceAndTaskAccessIsScopedToAuthenticatedOwner() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("owned-project"));
        Queue<Runnable> queued = new ArrayDeque<>();
        DefaultCodingService service = new DefaultCodingService(null, new InMemoryCodingTaskRepository(), null, null, null, queued::add);

        AtomicReference<CodingService.CodingWorkspace> workspace = new AtomicReference<>();
        AtomicReference<CodingService.CodingTask> task = new AtomicReference<>();
        CurrentUserContext.runAs("user-a", () -> {
            workspace.set(register(service, workspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST));
            task.set(service.startTask(new CodingService.StartTaskRequest(workspace.get().id(), "conv-a", "fake", "run tests")));
        });

        CurrentUserContext.runAs("user-b", () -> {
            assertThat(service.listWorkspaces()).isEmpty();
            assertThat(service.tasks()).isEmpty();
            assertThatThrownBy(() -> service.workspace(workspace.get().id()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown coding workspace");
            assertThatThrownBy(() -> service.task(task.get().id()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown coding task");
            assertThatThrownBy(() -> service.startTask(new CodingService.StartTaskRequest(workspace.get().id(), "conv-b", "fake", "run tests")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown coding workspace");
        });

        CurrentUserContext.runAs("user-a", () -> {
            assertThat(service.listWorkspaces()).extracting(CodingService.CodingWorkspace::id).containsExactly(workspace.get().id());
            assertThat(service.tasks()).extracting(CodingService.CodingTask::id).containsExactly(task.get().id());
            assertThat(service.task(task.get().id()).ownerUserId()).isEqualTo("user-a");
        });
    }

    @Test
    void nativeCodingAgentLoopReceivesAuthenticatedUserIdInImmutableRequestContext() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("auth-context-project"));
        AtomicReference<String> runtimeUserId = new AtomicReference<>();
        ToolCallingRuntime runtime = request -> {
            runtimeUserId.set(String.valueOf(request.context().get("userId")));
            return new com.jarvis.tools.runtime.ToolCallingResult(
                    true,
                    "done",
                    List.of(),
                    List.of(),
                    new ToolLoopTerminationInfo(ToolLoopTerminationReason.COMPLETED, true, true, 1, 1, 0, 0, 0, 0,
                            "", "", "", "", "done", "", List.of(), false, true)
            );
        };
        DefaultCodingService service = synchronousService(runtime);

        AtomicReference<CodingService.CodingTask> task = new AtomicReference<>();
        CurrentUserContext.runAs("user-a", () -> {
            var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST);
            task.set(service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-a", "fake", "run")));
        });

        assertThat(runtimeUserId).hasValue("user-a");
        CurrentUserContext.runAs("user-a", () -> assertThat(service.task(task.get().id()).status())
                .isEqualTo(CodingService.CodingTaskStatus.COMPLETED));
    }

    @Test
    void cancelledTaskCannotBeCompletedByQueuedBackgroundLoop() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("cancel-project"));
        Queue<Runnable> queued = new ArrayDeque<>();
        ToolCallingRuntime runtime = request -> new com.jarvis.tools.runtime.ToolCallingResult(
                true,
                "should not win",
                List.of(),
                List.of(),
                new ToolLoopTerminationInfo(ToolLoopTerminationReason.COMPLETED, true, true, 1, 1, 0, 0, 0, 0,
                        "", "", "", "", "should not win", "", List.of(), false, true)
        );
        DefaultCodingService service = new DefaultCodingService(null, new InMemoryCodingTaskRepository(), () -> runtime, null, null, queued::add);
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST);
        var task = service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "fake", "run"));

        var cancelled = service.cancelTask(task.id(), new CodingService.CodingRequestContext("test-user", "", "conv-1"));
        queued.remove().run();

        assertThat(cancelled.status()).isEqualTo(CodingService.CodingTaskStatus.CANCELLED);
        assertThat(service.task(task.id()).status()).isEqualTo(CodingService.CodingTaskStatus.CANCELLED);
    }

    @Test
    void cancellationDuringLongRunningCommandStopsTaskAndPreventsCompletionOverwrite() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("long-command-project"));
        DefaultCodingService service = new DefaultCodingService(null, new InMemoryCodingTaskRepository(), null,
                null, null, Executors.newCachedThreadPool());
        var workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "project",
                workspaceRoot.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.EDIT_AND_TEST,
                "",
                longCommand()
        ));
        var task = service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "fake", "long command"));

        awaitStatus(service, task.id(), CodingService.CodingTaskStatus.TESTING);
        var cancelled = service.cancelTask(task.id(), new CodingService.CodingRequestContext("test-user", "", "conv-1"));
        awaitStatus(service, task.id(), CodingService.CodingTaskStatus.CANCELLED);

        assertThat(cancelled.status()).isEqualTo(CodingService.CodingTaskStatus.CANCELLED);
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(service.task(task.id()).status()).isEqualTo(CodingService.CodingTaskStatus.CANCELLED);
    }

    @Test
    void riskyCodingToolCallCreatesApprovalAndStopsNativeLoop() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("approval-project"));
        Files.writeString(workspaceRoot.resolve("delete-me.txt"), "temporary", StandardCharsets.UTF_8);
        AtomicReference<ToolCallingRuntime> runtime = new AtomicReference<>();
        DefaultCodingService service = new DefaultCodingService(null, new InMemoryCodingWorkspaceRepository(),
                new InMemoryCodingTaskRepository(), new InMemoryCodingApprovalRepository(),
                runtime::get, null, new NoopCognitiveEventBus(), Runnable::run);
        CodingTool codingTool = new CodingTool(service);
        NativeToolLoopService nativeLoop = new NativeToolLoopService(
                List.of(new DeleteFileProvider()),
                new SingleToolManager(codingTool),
                query -> ToolIntent.CODING_WORKSPACE,
                new ToolRuntimeProperties(true, 4, 4, 1, 60, "native"),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper(),
                new NativeToolSchemaMapper(new SingleToolRegistry(codingTool.definition())),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );
        runtime.set(nativeLoop::execute);
        var workspace = register(service, workspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST);

        var task = service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "stub", "delete temp file"));

        assertThat(service.task(task.id()).status()).isEqualTo(CodingService.CodingTaskStatus.WAITING_FOR_APPROVAL);
        String approvalId = service.approvals(task.id()).getFirst().id();
        assertThat(service.approvals(task.id()))
                .singleElement()
                .satisfies(approval -> {
                    assertThat(approval.operation()).isEqualTo("FILE_DELETE");
                    assertThat(approval.status()).isEqualTo(CodingService.CodingApprovalStatus.PENDING);
                    assertThat(approval.argumentsDigest()).isNotBlank();
                });
        String digest = service.approvals(task.id()).getFirst().argumentsDigest();
        assertThat(service.requestApproval(task.id(), "FILE_DELETE", "repeat", "HIGH", digest).id()).isEqualTo(approvalId);
        assertThat(service.approvals(task.id())).hasSize(1);
        CurrentUserContext.runAs("user-b", () -> assertThatThrownBy(() ->
                service.approve(task.id(), approvalId, new CodingService.CodingRequestContext("user-b", "", "conv-b")))
                .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void sqliteCodingRepositoriesPersistWorkspacesTasksAndRecoverActiveTasksAsInterrupted() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("persistent-project"));
        Path secondWorkspaceRoot = Files.createDirectories(tempDir.resolve("persistent-project-2"));
        Path database = tempDir.resolve("coding.db");
        SQLiteConnectionFactory connectionFactory = new SQLiteConnectionFactory(new MemoryProperties(database.toString(), 20, null, null, null, null));
        SQLiteMemoryInitializer initializer = new SQLiteMemoryInitializer(connectionFactory);
        initializer.afterPropertiesSet();
        initializer.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper();
        SQLiteCodingWorkspaceRepository workspaceRepository = new SQLiteCodingWorkspaceRepository(connectionFactory, objectMapper);
        SQLiteCodingTaskRepository taskRepository = new SQLiteCodingTaskRepository(connectionFactory, objectMapper);
        Queue<Runnable> queued = new ArrayDeque<>();

        DefaultCodingService first = new DefaultCodingService(null, workspaceRepository, taskRepository,
                new InMemoryCodingApprovalRepository(), null, null, null, queued::add);
        first.afterPropertiesSet();
        AtomicReference<CodingService.CodingWorkspace> workspace = new AtomicReference<>();
        AtomicReference<CodingService.CodingTask> activeTask = new AtomicReference<>();
        AtomicReference<CodingService.CodingTask> cancelledTask = new AtomicReference<>();
        CurrentUserContext.runAs("user-a", () -> {
            workspace.set(register(first, workspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST));
            activeTask.set(first.startTask(new CodingService.StartTaskRequest(workspace.get().id(), "conv-a", "fake", "run")));
            // A workspace only ever allows one active coding task at a time (see
            // DefaultCodingService#startTask's executionRegistry.hasActiveWorkspace guard) - the
            // task above never finishes (its executor is a no-op queue), so the cancelled task
            // below needs its own workspace rather than reusing the same one.
            var secondWorkspace = register(first, secondWorkspaceRoot, CodingService.AutonomyLevel.EDIT_AND_TEST);
            var task = first.startTask(new CodingService.StartTaskRequest(secondWorkspace.id(), "conv-a", "fake", "cancel me"));
            cancelledTask.set(first.cancelTask(task.id(), new CodingService.CodingRequestContext("user-a", "", "conv-a")));
        });

        DefaultCodingService second = new DefaultCodingService(null, workspaceRepository, taskRepository,
                new InMemoryCodingApprovalRepository(), null, null, null, Runnable::run);
        second.afterPropertiesSet();

        CurrentUserContext.runAs("user-a", () -> {
            assertThat(second.listWorkspaces()).extracting(CodingService.CodingWorkspace::id).contains(workspace.get().id());
            assertThat(second.task(activeTask.get().id()).status()).isEqualTo(CodingService.CodingTaskStatus.INTERRUPTED);
            assertThat(second.task(cancelledTask.get().id()).status()).isEqualTo(CodingService.CodingTaskStatus.CANCELLED);
        });
        CurrentUserContext.runAs("user-b", () -> {
            assertThat(second.listWorkspaces()).isEmpty();
            assertThat(second.tasks()).isEmpty();
        });
    }

    @Test
    void nativeCodingAgentLoopUsesFailedTestOutputThenPatchesRetestsDiffsAndCompletes() throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("repo"));
        run(workspaceRoot, "git", "init");
        run(workspaceRoot, "git", "config", "user.email", "test@example.com");
        run(workspaceRoot, "git", "config", "user.name", "Test User");
        Files.writeString(workspaceRoot.resolve("marker.txt"), "broken\n", StandardCharsets.UTF_8);
        run(workspaceRoot, "git", "add", "marker.txt");
        run(workspaceRoot, "git", "commit", "-m", "initial");

        AtomicReference<ToolCallingRuntime> runtime = new AtomicReference<>();
        DefaultCodingService service = new DefaultCodingService(null, new InMemoryCodingTaskRepository(),
                runtime::get, null, new NoopCognitiveEventBus(), Runnable::run);
        CodingTool codingTool = new CodingTool(service);
        FakeProvider fakeProvider = new FakeProvider();
        NativeToolLoopService nativeLoop = new NativeToolLoopService(
                List.of(fakeProvider),
                new SingleToolManager(codingTool),
                query -> ToolIntent.CODING_WORKSPACE,
                new ToolRuntimeProperties(true, 12, 12, 1, 60, "native"),
                new NoopCognitiveEventBus(),
                new ToolRuntimeDebugService(),
                new ObjectMapper(),
                new NativeToolSchemaMapper(new SingleToolRegistry(codingTool.definition())),
                new StoreAuditDatasetService(new NoopCognitiveEventBus())
        );
        runtime.set(nativeLoop::execute);

        var workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "repo",
                workspaceRoot.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.EDIT_AND_TEST,
                "",
                contentTestCommand()
        ));

        var accepted = service.startTask(new CodingService.StartTaskRequest(workspace.id(), "conv-1", "stub", "Fix marker test"));
        var task = service.task(accepted.id());

        assertThat(task.status()).isEqualTo(CodingService.CodingTaskStatus.COMPLETED);
        assertThat(task.iteration()).isGreaterThanOrEqualTo(6);
        assertThat(fakeProvider.calls()).isGreaterThanOrEqualTo(6);
        assertThat(fakeProvider.observedFailedTest()).isTrue();
        assertThat(fakeProvider.observedSuccessfulRetest()).isTrue();
        assertThat(Files.readString(workspaceRoot.resolve("marker.txt"), StandardCharsets.UTF_8)).contains("fixed");
        assertThat(task.testResult()).contains("operation=TEST_RUN").contains("exitCode=1").contains("exitCode=0");
        assertThat(task.changedFiles())
                .containsEntry("gitDiffChanged", "true")
                .hasEntrySatisfying("toolCallOrder", order -> assertThat(order).contains("coding.TEST_RUN").contains("coding.FILE_PATCH").contains("coding.GIT_DIFF"));
        assertThat(task.failureReason()).isBlank();
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

    private DefaultCodingService synchronousService(ToolCallingRuntime runtime) {
        return new DefaultCodingService(null, new InMemoryCodingTaskRepository(), () -> runtime, null, null, Runnable::run);
    }

    private void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        assertThat(process.waitFor()).isZero();
    }

    private String command(String script) {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? script
                : script.replace("echo ", "printf ");
    }

    private String failingCommand() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "cmd.exe /c exit 7"
                : "false";
    }

    private static String contentTestCommand() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "findstr fixed marker.txt"
                : "grep -q fixed marker.txt";
    }

    private void awaitStatus(DefaultCodingService service, String taskId, CodingService.CodingTaskStatus status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (service.task(taskId).status() == status) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        assertThat(service.task(taskId).status()).isEqualTo(status);
    }

    private String longCommand() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "ping 127.0.0.1 -n 30 > nul"
                : "sleep 30";
    }

    private static final class FakeProvider implements AIProvider {

        private final AtomicInteger calls = new AtomicInteger();
        private boolean observedFailedTest;
        private boolean observedSuccessfulRetest;

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return new ChatResponse("");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }

        @Override
        public ModelResponse toolChat(Brain brain, List<ModelMessage> messages, List<NativeToolDefinition> tools, AIJobType jobType) {
            int turn = calls.incrementAndGet();
            if (hasExitCode(messages, 1)) {
                observedFailedTest = true;
            }
            if (hasExitCode(messages, 0)) {
                observedSuccessfulRetest = true;
            }
            return switch (turn) {
                case 1 -> tool("call-1", "coding__workspace_inspect", Map.of());
                case 2 -> tool("call-2", "coding__file_read", Map.of("path", "marker.txt"));
                case 3 -> tool("call-3", "coding__test_run", Map.of());
                case 4 -> {
                    assertThat(observedFailedTest).isTrue();
                    yield tool("call-4", "coding__file_patch", Map.of(
                            "path", "marker.txt",
                            "expected", "broken",
                            "replacement", "fixed"
                    ));
                }
                case 5 -> tool("call-5", "coding__test_run", Map.of("command", contentTestCommand()));
                case 6 -> {
                    assertThat(observedSuccessfulRetest).isTrue();
                    yield tool("call-6", "coding__git_diff", Map.of());
                }
                default -> new ModelResponse("Task complete after patch, successful retest, and git diff inspection.", "",
                        List.of(), "stop", new ModelUsage(0, 0, 0));
            };
        }

        int calls() {
            return calls.get();
        }

        boolean observedFailedTest() {
            return observedFailedTest;
        }

        boolean observedSuccessfulRetest() {
            return observedSuccessfulRetest;
        }

        private ModelResponse tool(String id, String name, Map<String, Object> arguments) {
            return new ModelResponse("", "", List.of(new ModelToolCall(id, name, arguments)), "tool_calls", new ModelUsage(0, 0, 0));
        }

        private boolean hasExitCode(List<ModelMessage> messages, int exitCode) {
            String expectedJson = "\"exitCode\":" + exitCode;
            String expectedToString = "exitCode=" + exitCode;
            return messages.stream()
                    .map(ModelMessage::content)
                    .anyMatch(content -> content.contains(expectedJson) || content.contains(expectedToString));
        }
    }

    private static final class DeleteFileProvider implements AIProvider {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String provider() {
            return "stub";
        }

        @Override
        public ChatResponse chat(Brain brain, String prompt) {
            return new ChatResponse("");
        }

        @Override
        public void stream(String conversationId, Brain brain, String prompt, ChatEventSink eventSink) {
        }

        @Override
        public ModelResponse toolChat(Brain brain, List<ModelMessage> messages, List<NativeToolDefinition> tools, AIJobType jobType) {
            if (calls.incrementAndGet() == 1) {
                return new ModelResponse("", "", List.of(new ModelToolCall("call-delete", "coding__file_delete",
                        Map.of("path", "delete-me.txt", "approved", false))), "tool_calls", new ModelUsage(0, 0, 0));
            }
            return new ModelResponse("waiting", "", List.of(), "stop", new ModelUsage(0, 0, 0));
        }
    }

    private static final class SingleToolManager implements ToolManager {

        private final JarvisTool tool;

        private SingleToolManager(JarvisTool tool) {
            this.tool = tool;
        }

        @Override
        public List<JarvisTool> listTools() {
            return List.of(tool);
        }

        @Override
        public Optional<JarvisTool> findTool(String name) {
            return tool.getName().equalsIgnoreCase(name) ? Optional.of(tool) : Optional.empty();
        }

        @Override
        public ToolResult execute(ToolRequest request) {
            return tool.execute(request);
        }
    }

    private static final class SingleToolRegistry implements ToolRegistry {

        private final ToolDefinition definition;

        private SingleToolRegistry(ToolDefinition definition) {
            this.definition = definition;
        }

        @Override
        public List<ToolDefinition> definitions() {
            return List.of(definition);
        }

        @Override
        public String promptSection() {
            return "Tool: coding workspace";
        }
    }

    private static final class NoopCognitiveEventBus implements CognitiveEventBus {

        @Override
        public void startRequest(String requestId, String conversationId, java.util.function.Consumer<com.jarvis.common.event.CognitiveEvent> sink) {
        }

        @Override
        public void finishRequest() {
        }

        @Override
        public void updateBrain(BrainType brain, String model) {
        }

        @Override
        public void publish(CognitiveEventType event, String status, String message, String nodeId, Map<String, Object> metadata) {
        }
    }
}
