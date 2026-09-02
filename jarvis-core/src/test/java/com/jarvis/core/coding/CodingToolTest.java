package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import com.jarvis.tools.ToolRequest;
import com.jarvis.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CodingToolTest {

    @Test
    void fileReadDelegatesToCodingServiceWithInjectedWorkspace() {
        CapturingCodingService service = new CapturingCodingService();
        CodingTool tool = new CodingTool(service);

        ToolResult result = tool.execute(new ToolRequest("coding", "FILE_READ", "conv-1", "req-1",
                "read project file", "test", Map.of(
                "_activeCodingWorkspaceId", "workspace-1",
                "_activeCodingWorkspaceName", "Test",
                "_activeCodingWorkspaceHost", "WINDOWS",
                "path", "WINDOWS_ONLY.txt"
        )));

        assertThat(result.success()).isTrue();
        assertThat(service.readWorkspaceId).hasValue("workspace-1");
        assertThat(service.readPath).hasValue("WINDOWS_ONLY.txt");
        assertThat(result.data()).containsEntry("workspaceId", "workspace-1");
        assertThat(result.data()).containsEntry("workspaceName", "Test");
    }

    @Test
    void missingActiveWorkspaceReturnsActionableError() {
        CodingTool tool = new CodingTool(new CapturingCodingService());

        ToolResult result = tool.execute(new ToolRequest("coding", "FILE_READ", "conv-1", "req-1",
                "read project file", "test", Map.of(
                "path", "WINDOWS_ONLY.txt"
        )));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("NO_ACTIVE_CODING_WORKSPACE");
        assertThat(result.errorMessage()).contains("Wybierz workspace");
    }

    @Test
    void schemaExposesCodingOperationsWithoutWorkspaceArgument() {
        CodingTool tool = new CodingTool(new CapturingCodingService());

        assertThat(tool.definition().operations())
                .extracting(operation -> "coding__" + operation.name().toLowerCase(java.util.Locale.ROOT))
                .contains(
                        "coding__workspace_inspect",
                        "coding__file_list",
                        "coding__file_search",
                        "coding__file_read",
                        "coding__file_write",
                        "coding__file_patch",
                        "coding__directory_create",
                        "coding__file_move",
                        "coding__file_delete",
                        "coding__git_status",
                        "coding__git_diff",
                        "coding__build_detect",
                        "coding__command_start",
                        "coding__command_poll",
                        "coding__command_cancel",
                        "coding__build_run",
                        "coding__test_run"
                );
        assertThat(tool.definition().operations())
                .flatExtracting(CodingServiceOperationAssertions::argumentNames)
                .doesNotContain("workspaceId", "windowsPath");
    }

    private static final class CodingServiceOperationAssertions {
        private static List<String> argumentNames(com.jarvis.tools.schema.ToolOperationDefinition operation) {
            return operation.arguments().stream()
                    .map(com.jarvis.tools.schema.ToolArgumentDefinition::name)
                    .toList();
        }
    }

    private static final class CapturingCodingService implements CodingService {
        private final AtomicReference<String> readWorkspaceId = new AtomicReference<>();
        private final AtomicReference<String> readPath = new AtomicReference<>();

        @Override
        public List<CodingWorkspace> listWorkspaces() {
            return List.of();
        }

        @Override
        public CodingWorkspace registerWorkspace(RegisterWorkspaceRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodingWorkspace workspace(String workspaceId) {
            return workspaceRecord(workspaceId);
        }

        @Override
        public CodingWorkspace refreshWorkspace(String workspaceId) {
            return workspaceRecord(workspaceId);
        }

        @Override
        public void removeWorkspace(String workspaceId) {
        }

        @Override
        public List<WorkspaceFileEntry> listFiles(String workspaceId, String path) {
            return List.of(new WorkspaceFileEntry("WINDOWS_ONLY.txt", false, 7));
        }

        @Override
        public FileContent readFile(String workspaceId, String path, Integer startLine, Integer endLine) {
            readWorkspaceId.set(workspaceId);
            readPath.set(path);
            return new FileContent(path, 1, 1, "windows-only-content", "sha");
        }

        @Override
        public List<SearchMatch> search(String workspaceId, FileSearchRequest request) {
            return List.of(new SearchMatch("WINDOWS_ONLY.txt", 1, "windows-only-content"));
        }

        @Override
        public FileContent writeFile(String workspaceId, FileWriteRequest request) {
            return new FileContent(request.path(), 1, 1, request.content(), "sha");
        }

        @Override
        public FileContent patchFile(String workspaceId, PatchRequest request) {
            return new FileContent(request.path(), 1, 1, request.replacement(), "sha");
        }

        @Override
        public WorkspaceFileEntry createDirectory(String workspaceId, DirectoryCreateRequest request) {
            return new WorkspaceFileEntry(request.path(), true, 0);
        }

        @Override
        public WorkspaceFileEntry moveFile(String workspaceId, FileMoveRequest request) {
            return new WorkspaceFileEntry(request.targetPath(), false, 0);
        }

        @Override
        public void deleteFile(String workspaceId, FileDeleteRequest request) {
        }

        @Override
        public CommandResult runCommand(String workspaceId, CommandRequest request) {
            return new CommandResult("pid", request.command(), 0, false, "ok", "");
        }

        @Override
        public CommandResult commandPoll(String workspaceId, String processId) {
            return new CommandResult(processId, "", 0, false, "ok", "");
        }

        @Override
        public CommandResult commandCancel(String workspaceId, String processId) {
            return new CommandResult(processId, "", 0, false, "cancelled", "");
        }

        @Override
        public GitSnapshot gitSnapshot(String workspaceId) {
            return new GitSnapshot("main", "abc", "", "diff");
        }

        @Override
        public Map<String, Object> buildDetect(String workspaceId) {
            return Map.of("detectedBuildSystems", List.of());
        }

        @Override
        public CommandResult buildRun(String workspaceId, BuildRunRequest request) {
            return new CommandResult("pid", request.command(), 0, false, "ok", "");
        }

        @Override
        public CommandResult testRun(String workspaceId, BuildRunRequest request) {
            return new CommandResult("pid", request.command(), 0, false, "ok", "");
        }

        @Override
        public CodingTask startTask(StartTaskRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CodingTask task(String taskId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<CodingTask> tasks() {
            return List.of();
        }

        private CodingWorkspace workspaceRecord(String workspaceId) {
            Instant now = Instant.now();
            return new CodingWorkspace(workspaceId, "Test", "D:\\JARVIS CODING\\Test", WorkspaceHost.WINDOWS,
                    "Unknown", List.of(), true, "main", "abc", "", AutonomyLevel.ASK_BEFORE_WRITE,
                    "", "", now, "test-user", now, now);
        }
    }
}
