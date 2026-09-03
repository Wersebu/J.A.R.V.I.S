package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.tools.mcp.McpException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void setAuthenticatedUser() {
        CurrentUserContext.set("test-user");
    }

    @AfterEach
    void clearAuthenticatedUser() {
        CurrentUserContext.clear();
    }

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
    void browserEvaluateIsRoutedThroughTheWindowsBridgeWithDefaultsApplied() {
        RecordingBridge bridge = new RecordingBridge();
        DefaultCodingService service = new DefaultCodingService(bridge);
        CodingService.CodingWorkspace workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "demo", "D:\\workspace", CodingService.WorkspaceHost.WINDOWS, "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE, "", ""
        ));

        Map<String, Object> result = service.browserEvaluate(workspace.id(),
                new CodingService.BrowserEvaluateRequest(0, "", "window.gameState.score", 0));

        assertThat(bridge.operations()).contains("browser_evaluate");
        assertThat(bridge.lastPayload().get("port")).isEqualTo(9222);
        assertThat(bridge.lastPayload().get("timeoutSeconds")).isEqualTo(10L);
        assertThat(bridge.lastPayload()).doesNotContainKey("tabId");
        assertThat(result.get("value")).isEqualTo(42);
    }

    @Test
    void browserListTabsOnAServerWorkspaceIsRejectedWithoutEverReachingTheBridge() {
        RecordingBridge bridge = new RecordingBridge();
        DefaultCodingService service = new DefaultCodingService(bridge);
        CodingService.CodingWorkspace workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "server", tempDir.toString(), CodingService.WorkspaceHost.SERVER, "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE, "", ""
        ));

        assertThatThrownBy(() -> service.browserListTabs(workspace.id(), 9222))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Windows-hosted");
        assertThat(bridge.operations()).doesNotContain("browser_list_tabs");
    }

    @Test
    void browserScreenshotDescribeReturnsTheVisionModelsAnswerWithoutEverAskingTheActiveChatModel() {
        RecordingBridge bridge = new RecordingBridge();
        DefaultCodingService service = new DefaultCodingService(bridge);
        RecordingVisionProvider vision = new RecordingVisionProvider("A red button in the top-right corner.");
        service.visionDescriptionProvider = vision;
        service.visionModel = "moondream";
        service.visionForceCpu = true;
        CodingService.CodingWorkspace workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "demo", "D:\\workspace", CodingService.WorkspaceHost.WINDOWS, "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE, "", ""
        ));

        Map<String, Object> result = service.browserScreenshotDescribe(workspace.id(),
                new CodingService.BrowserScreenshotDescribeRequest(0, "", "describe precisely the top-right corner"));

        assertThat(bridge.operations()).contains("browser_screenshot");
        assertThat(vision.lastModel()).isEqualTo("moondream");
        assertThat(vision.lastQuestion()).isEqualTo("describe precisely the top-right corner");
        assertThat(vision.lastForceCpu()).isTrue();
        assertThat(result.get("answer")).isEqualTo("A red button in the top-right corner.");
    }

    @Test
    void browserScreenshotDescribeFailsClearlyWithoutAConfiguredVisionModel() {
        RecordingBridge bridge = new RecordingBridge();
        DefaultCodingService service = new DefaultCodingService(bridge);
        CodingService.CodingWorkspace workspace = service.registerWorkspace(new CodingService.RegisterWorkspaceRequest(
                "demo", "D:\\workspace", CodingService.WorkspaceHost.WINDOWS, "AUTO",
                CodingService.AutonomyLevel.ASK_BEFORE_WRITE, "", ""
        ));

        assertThatThrownBy(() -> service.browserScreenshotDescribe(workspace.id(),
                new CodingService.BrowserScreenshotDescribeRequest(0, "", "what does the health bar show")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vision model");
        assertThat(bridge.operations()).doesNotContain("browser_screenshot");
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
        private Map<String, Object> lastPayload = Map.of();

        private List<String> operations() {
            return operations;
        }

        private Map<String, Object> lastPayload() {
            return lastPayload;
        }

        @Override
        public String codingStatus() {
            return "CONNECTED";
        }

        @Override
        public Map<String, Object> codingRequest(String operation, Map<String, Object> payload, Duration timeout) {
            operations.add(operation);
            lastPayload = payload;
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
                case "browser_evaluate" -> Map.of(
                        "expression", payload.get("expression"),
                        "threw", false,
                        "type", "number",
                        "value", 42
                );
                case "browser_screenshot" -> Map.of("port", payload.get("port"), "format", "png", "dataBase64", "iVBORw0KGgoAAAANS");
                default -> throw new AssertionError("Unexpected operation: " + operation);
            };
        }
    }

    private static final class RecordingVisionProvider implements com.jarvis.common.ai.VisionDescriptionProvider {
        private final String answer;
        private String lastModel;
        private String lastQuestion;
        private boolean lastForceCpu;

        private RecordingVisionProvider(String answer) {
            this.answer = answer;
        }

        private String lastModel() {
            return lastModel;
        }

        private String lastQuestion() {
            return lastQuestion;
        }

        private boolean lastForceCpu() {
            return lastForceCpu;
        }

        @Override
        public String describeImage(String model, String question, String base64Image, boolean forceCpu) {
            this.lastModel = model;
            this.lastQuestion = question;
            this.lastForceCpu = forceCpu;
            return answer;
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
