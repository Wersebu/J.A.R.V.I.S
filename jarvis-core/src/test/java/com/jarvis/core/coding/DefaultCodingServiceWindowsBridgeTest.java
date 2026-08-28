package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.mcp.McpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultCodingServiceWindowsBridgeTest {

    @TempDir
    private Path tempDir;

    @Test
    void windowsDrivePathIsValidatedByWindowsBridgeNotByLocalFilesystem() {
        RecordingBridge bridge = new RecordingBridge();
        DefaultCodingService service = new DefaultCodingService(bridge);

        CodingService.CodingWorkspace workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "demo",
                "D:\\workspace",
                CodingService.WorkspaceHost.WINDOWS,
                "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE,
                "",
                ""
        ));
        CodingService.FileContent content = service.readFile(workspace.id(), "only-on-windows.txt", null, null);

        assertThat(workspace.host()).isEqualTo(CodingService.WorkspaceHost.WINDOWS);
        assertThat(workspace.windowsPath()).isEqualTo("D:\\workspace");
        assertThat(content.content()).isEqualTo("from windows");
        assertThat(bridge.operations()).containsExactly("workspace_validate", "workspace_inspect", "file_read");
    }

    @Test
    void windowsWorkspaceNeverFallsBackToLocalFilesystemWhenBridgeIsMissing() {
        DefaultCodingService service = new DefaultCodingService();

        assertThatThrownBy(() -> service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "demo",
                "D:\\workspace",
                CodingService.WorkspaceHost.WINDOWS,
                "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE,
                "",
                ""
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Windows Coding Executor is unavailable");
    }

    @Test
    void unavailableWindowsBridgeReturnsReadableErrorWithoutLocalFallback() {
        DefaultCodingService service = new DefaultCodingService(new FailingBridge("Windows Coding Executor is unavailable: no Windows Bridge session is connected."));

        assertThatThrownBy(() -> service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "demo",
                "D:\\workspace",
                CodingService.WorkspaceHost.WINDOWS,
                "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE,
                "",
                ""
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no Windows Bridge session is connected");
    }

    @Test
    void serverWorkspaceStillRunsOnLocalServerFilesystem() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("server-project"));
        Files.writeString(root.resolve("README.md"), "server local", StandardCharsets.UTF_8);
        RecordingBridge bridge = new RecordingBridge();
        DefaultCodingService service = new DefaultCodingService(bridge);

        CodingService.CodingWorkspace workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "server",
                root.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE,
                "",
                ""
        ));

        assertThat(workspace.host()).isEqualTo(CodingService.WorkspaceHost.SERVER);
        assertThat(service.readFile(workspace.id(), "README.md", null, null).content()).isEqualTo("server local");
        assertThat(bridge.operations()).isEmpty();
    }

    private static final class RecordingBridge implements WindowsCodingBridgeGateway {
        private final List<String> operations = new ArrayList<>();

        private List<String> operations() {
            return operations;
        }

        @Override
        public String codingStatus() {
            return "CONNECTED";
        }

        @Override
        public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
            operations.add(operation);
            return switch (operation) {
                case "workspace_validate" -> Map.of("canonicalPath", payload.get("rootPath"), "name", "workspace");
                case "workspace_inspect" -> Map.of(
                        "canonicalPath", payload.get("rootPath"),
                        "name", "workspace",
                        "detectedBuildSystems", List.of("Unknown"),
                        "gitRepository", false,
                        "gitBranch", "",
                        "gitHeadCommit", "",
                        "gitStatus", "",
                        "buildCommand", "",
                        "testCommand", ""
                );
                case "file_read" -> Map.of(
                        "path", payload.get("path"),
                        "startLine", 1,
                        "endLine", 1,
                        "content", "from windows",
                        "sha256", "abc"
                );
                default -> throw new AssertionError("Unexpected operation: " + operation);
            };
        }
    }

    private static final class FailingBridge implements WindowsCodingBridgeGateway {
        private final String message;

        private FailingBridge(String message) {
            this.message = message;
        }

        @Override
        public String codingStatus() {
            return "WINDOWS_BRIDGE_UNAVAILABLE";
        }

        @Override
        public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
            throw new McpException(message);
        }
    }
}
