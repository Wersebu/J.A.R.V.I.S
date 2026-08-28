package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.tools.mcp.McpException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Controlled coding workspace implementation for the first vertical Coding Agent flow.
 */
@Service
public class DefaultCodingService implements CodingService {

    private static final int DEFAULT_MAX_SEARCH_RESULTS = 100;
    private static final int DEFAULT_MAX_OUTPUT = 64_000;
    private static final long MAX_READ_BYTES = 1_000_000L;
    private static final java.time.Duration WINDOWS_FAST_TIMEOUT = java.time.Duration.ofSeconds(10);
    private static final java.time.Duration WINDOWS_COMMAND_TIMEOUT = java.time.Duration.ofMinutes(16);

    private final Map<String, WorkspaceState> workspaces = new ConcurrentHashMap<>();
    private final Map<String, CodingTask> tasks = new ConcurrentHashMap<>();
    private final WindowsCodingBridgeGateway windowsBridgeGateway;

    public DefaultCodingService() {
        this.windowsBridgeGateway = null;
    }

    @Autowired
    public DefaultCodingService(ObjectProvider<WindowsCodingBridgeGateway> windowsBridgeGateway) {
        this.windowsBridgeGateway = windowsBridgeGateway.getIfAvailable();
    }

    DefaultCodingService(WindowsCodingBridgeGateway windowsBridgeGateway) {
        this.windowsBridgeGateway = windowsBridgeGateway;
    }

    @Override
    public List<CodingWorkspace> listWorkspaces() {
        return workspaces.values().stream()
                .sorted(Comparator.comparing(state -> state.workspace.lastUsedAt(), Comparator.reverseOrder()))
                .map(state -> state.workspace)
                .toList();
    }

    @Override
    public CodingWorkspace registerWorkspace(RegisterWorkspaceRequest request) {
        if (request == null || blank(request.windowsPath())) {
            throw new IllegalArgumentException("Workspace path is required.");
        }
        CodingService.WorkspaceHost host = request.host() == null ? CodingService.WorkspaceHost.WINDOWS : request.host();
        String id = UUID.randomUUID().toString();
        AutonomyLevel autonomy = request.autonomyLevel() == null ? AutonomyLevel.ASK_BEFORE_WRITE : request.autonomyLevel();
        CodingWorkspace workspace;
        WorkspaceState state;
        if (host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> validated = windowsRequest("workspace_validate", Map.of("rootPath", request.windowsPath()), WINDOWS_FAST_TIMEOUT);
            String canonicalPath = string(validated, "canonicalPath", request.windowsPath());
            String name = blank(request.name()) ? string(validated, "name", canonicalPath) : request.name();
            workspace = inspectWindowsWorkspace(
                    id,
                    canonicalPath,
                    name,
                    blank(request.projectType()) ? "AUTO" : request.projectType(),
                    autonomy,
                    request.buildCommand(),
                    request.testCommand()
            );
            state = new WorkspaceState(id, null, canonicalPath, host, workspace);
        } else {
            Path root = canonicalExistingDirectory(Path.of(request.windowsPath()));
            workspace = inspectWorkspace(
                    id,
                    root,
                    blank(request.name()) ? root.getFileName().toString() : request.name(),
                    blank(request.projectType()) ? "AUTO" : request.projectType(),
                    autonomy,
                    request.buildCommand(),
                    request.testCommand()
            );
            state = new WorkspaceState(id, root, root.toString(), host, workspace);
        }
        workspaces.put(id, state);
        return workspace;
    }

    @Override
    public CodingWorkspace workspace(String workspaceId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        state.workspace = touch(state.workspace);
        return state.workspace;
    }

    @Override
    public CodingWorkspace refreshWorkspace(String workspaceId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        CodingWorkspace current = state.workspace;
        state.workspace = state.host == CodingService.WorkspaceHost.WINDOWS
                ? inspectWindowsWorkspace(current.id(), state.windowsPath, current.name(), current.projectType(),
                current.autonomyLevel(), current.buildCommand(), current.testCommand())
                : inspectWorkspace(current.id(), state.root, current.name(), current.projectType(),
                current.autonomyLevel(), current.buildCommand(), current.testCommand());
        return state.workspace;
    }

    @Override
    public void removeWorkspace(String workspaceId) {
        if (workspaces.remove(workspaceId) == null) {
            throw new IllegalArgumentException("Unknown coding workspace: " + workspaceId);
        }
    }

    @Override
    public List<WorkspaceFileEntry> listFiles(String workspaceId, String path) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> response = windowsRequest("file_list", Map.of(
                    "rootPath", state.windowsPath,
                    "path", path == null ? "" : path
            ), WINDOWS_FAST_TIMEOUT);
            return maps(response.get("files")).stream()
                    .map(file -> new WorkspaceFileEntry(
                            string(file, "path", ""),
                            bool(file, "directory"),
                            longValue(file, "size", 0)
                    ))
                    .toList();
        }
        Path directory = resolveInsideWorkspace(state, path);
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Path is not a directory: " + path);
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator.comparing(candidate -> candidate.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .limit(500)
                    .map(candidate -> new WorkspaceFileEntry(relative(state, candidate), Files.isDirectory(candidate), size(candidate)))
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list workspace files: " + exception.getMessage(), exception);
        }
    }

    @Override
    public FileContent readFile(String workspaceId, String path, Integer startLine, Integer endLine) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> response = windowsRequest("file_read", Map.of(
                    "rootPath", state.windowsPath,
                    "path", path == null ? "" : path,
                    "startLine", startLine == null ? 0 : startLine,
                    "endLine", endLine == null ? 0 : endLine
            ), WINDOWS_FAST_TIMEOUT);
            return fileContent(response);
        }
        Path file = resolveInsideWorkspace(state, path);
        return readResolvedFile(state, file, startLine, endLine);
    }

    @Override
    public List<SearchMatch> search(String workspaceId, FileSearchRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        String query = request == null ? "" : request.query();
        if (blank(query)) {
            throw new IllegalArgumentException("Search query is required.");
        }
        int maxResults = request.maxResults() <= 0 ? DEFAULT_MAX_SEARCH_RESULTS : Math.min(request.maxResults(), 500);
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> response = windowsRequest("file_search", Map.of(
                    "rootPath", state.windowsPath,
                    "query", query,
                    "regex", request.regex(),
                    "maxResults", maxResults
            ), WINDOWS_FAST_TIMEOUT);
            return maps(response.get("matches")).stream()
                    .map(match -> new SearchMatch(
                            string(match, "path", ""),
                            (int) longValue(match, "line", 0),
                            string(match, "preview", "")
                    ))
                    .toList();
        }
        Pattern pattern = request.regex() ? Pattern.compile(query) : Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        List<SearchMatch> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(state.root)) {
            for (Path file : stream.filter(Files::isRegularFile).limit(20_000).toList()) {
                if (matches.size() >= maxResults || size(file) > MAX_READ_BYTES || isIgnored(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size() && matches.size() < maxResults; i++) {
                    String line = lines.get(i);
                    if (pattern.matcher(line).find()) {
                        matches.add(new SearchMatch(relative(state, file), i + 1, trim(line, 240)));
                    }
                }
            }
            return matches;
        } catch (IOException exception) {
            throw new IllegalStateException("Search failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public FileContent writeFile(String workspaceId, FileWriteRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        requireWriteAllowed(state);
        if (request == null || request.path() == null) {
            throw new IllegalArgumentException("File path is required.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            return fileContent(windowsRequest("file_write", Map.of(
                    "rootPath", state.windowsPath,
                    "path", request.path(),
                    "content", request.content() == null ? "" : request.content()
            ), WINDOWS_FAST_TIMEOUT));
        }
        Path file = resolveCreatableInsideWorkspace(state, request.path());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, request.content() == null ? "" : request.content(), StandardCharsets.UTF_8);
            return readResolvedFile(state, file, null, null);
        } catch (IOException exception) {
            throw new IllegalStateException("File write failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public FileContent patchFile(String workspaceId, PatchRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        requireWriteAllowed(state);
        if (request == null || request.path() == null || request.expected() == null || request.replacement() == null) {
            throw new IllegalArgumentException("Patch path, expected and replacement are required.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            return fileContent(windowsRequest("file_patch", Map.of(
                    "rootPath", state.windowsPath,
                    "path", request.path(),
                    "expected", request.expected(),
                    "replacement", request.replacement()
            ), WINDOWS_FAST_TIMEOUT));
        }
        Path file = resolveInsideWorkspace(state, request.path());
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            int index = content.indexOf(request.expected());
            if (index < 0) {
                throw new IllegalStateException("Patch conflict: expected content is no longer present.");
            }
            String updated = content.substring(0, index) + request.replacement() + content.substring(index + request.expected().length());
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            return readResolvedFile(state, file, null, null);
        } catch (IOException exception) {
            throw new IllegalStateException("Patch failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public WorkspaceFileEntry createDirectory(String workspaceId, DirectoryCreateRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        requireWriteAllowed(state);
        if (request == null || request.path() == null) {
            throw new IllegalArgumentException("Directory path is required.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> response = windowsRequest("directory_create", Map.of(
                    "rootPath", state.windowsPath,
                    "path", request.path()
            ), WINDOWS_FAST_TIMEOUT);
            return new WorkspaceFileEntry(string(response, "path", request.path()), true, 0);
        }
        Path directory = resolveCreatableInsideWorkspace(state, request.path());
        try {
            Files.createDirectories(directory);
            return new WorkspaceFileEntry(relative(state, directory), true, 0);
        } catch (IOException exception) {
            throw new IllegalStateException("Directory create failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public WorkspaceFileEntry moveFile(String workspaceId, FileMoveRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        requireWriteAllowed(state);
        if (request == null || request.sourcePath() == null || request.targetPath() == null) {
            throw new IllegalArgumentException("Source and target paths are required.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> response = windowsRequest("file_move", Map.of(
                    "rootPath", state.windowsPath,
                    "sourcePath", request.sourcePath(),
                    "targetPath", request.targetPath()
            ), WINDOWS_FAST_TIMEOUT);
            return new WorkspaceFileEntry(string(response, "targetPath", request.targetPath()), false, 0);
        }
        Path source = resolveInsideWorkspace(state, request.sourcePath());
        Path target = resolveCreatableInsideWorkspace(state, request.targetPath());
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            return new WorkspaceFileEntry(relative(state, target), Files.isDirectory(target), size(target));
        } catch (IOException exception) {
            throw new IllegalStateException("File move failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void deleteFile(String workspaceId, FileDeleteRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        requireWriteAllowed(state);
        if (request == null || request.path() == null) {
            throw new IllegalArgumentException("File path is required.");
        }
        if (!request.approved()) {
            throw new IllegalStateException("File delete requires explicit approval.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            windowsRequest("file_delete", Map.of(
                    "rootPath", state.windowsPath,
                    "path", request.path(),
                    "approved", true
            ), WINDOWS_FAST_TIMEOUT);
            return;
        }
        Path target = resolveInsideWorkspace(state, request.path());
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> paths = Files.walk(target)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            } else {
                Files.deleteIfExists(target);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("File delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public CommandResult runCommand(String workspaceId, CommandRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (request == null || blank(request.command())) {
            throw new IllegalArgumentException("Command is required.");
        }
        requireCommandAllowed(state, request.command());
        long timeoutSeconds = request.timeoutSeconds() <= 0 ? 60 : Math.min(request.timeoutSeconds(), 900);
        int maxOutput = request.maxOutputCharacters() <= 0 ? DEFAULT_MAX_OUTPUT : Math.min(request.maxOutputCharacters(), 250_000);
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> response = windowsRequest("command_start", Map.of(
                    "rootPath", state.windowsPath,
                    "command", request.command(),
                    "timeoutSeconds", timeoutSeconds,
                    "maxOutputCharacters", maxOutput
            ), WINDOWS_COMMAND_TIMEOUT);
            return commandResult(response, request.command());
        }
        String processId = UUID.randomUUID().toString();
        ProcessBuilder builder = shellCommand(request.command());
        builder.directory(state.root.toFile());
        try {
            Process process = builder.start();
            StreamCollector stdout = new StreamCollector(process.getInputStream(), maxOutput);
            StreamCollector stderr = new StreamCollector(process.getErrorStream(), maxOutput);
            Thread outThread = Thread.ofVirtual().start(stdout);
            Thread errThread = Thread.ofVirtual().start(stderr);
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }
            outThread.join(TimeUnit.SECONDS.toMillis(2));
            errThread.join(TimeUnit.SECONDS.toMillis(2));
            int exitCode = finished ? process.exitValue() : -1;
            return new CommandResult(processId, request.command(), exitCode, !finished, stdout.content(), stderr.content());
        } catch (IOException exception) {
            throw new IllegalStateException("Command failed to start: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Command interrupted.", exception);
        }
    }

    @Override
    public CommandResult commandPoll(String workspaceId, String processId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (blank(processId)) {
            throw new IllegalArgumentException("Process id is required.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            return commandResult(windowsRequest("command_poll", Map.of(
                    "rootPath", state.windowsPath,
                    "processId", processId
            ), WINDOWS_FAST_TIMEOUT), "");
        }
        throw new IllegalStateException("Asynchronous command polling is only available through the Windows Coding Executor.");
    }

    @Override
    public CommandResult commandCancel(String workspaceId, String processId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (blank(processId)) {
            throw new IllegalArgumentException("Process id is required.");
        }
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            return commandResult(windowsRequest("command_cancel", Map.of(
                    "rootPath", state.windowsPath,
                    "processId", processId
            ), WINDOWS_FAST_TIMEOUT), "");
        }
        throw new IllegalStateException("Asynchronous command cancellation is only available through the Windows Coding Executor.");
    }

    @Override
    public GitSnapshot gitSnapshot(String workspaceId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            Map<String, Object> status = windowsRequest("git_status", Map.of("rootPath", state.windowsPath), WINDOWS_FAST_TIMEOUT);
            Map<String, Object> diff = windowsRequest("git_diff", Map.of("rootPath", state.windowsPath), WINDOWS_FAST_TIMEOUT);
            return new GitSnapshot(
                    string(status, "branch", ""),
                    string(status, "headCommit", ""),
                    string(status, "status", ""),
                    string(diff, "diff", "")
            );
        }
        return new GitSnapshot(
                gitLine(state.root, "rev-parse", "--abbrev-ref", "HEAD"),
                gitLine(state.root, "rev-parse", "HEAD"),
                gitText(state.root, "status", "--short"),
                gitText(state.root, "diff", "--", ".")
        );
    }

    @Override
    public Map<String, Object> buildDetect(String workspaceId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (state.host == CodingService.WorkspaceHost.WINDOWS) {
            return windowsRequest("build_detect", Map.of("rootPath", state.windowsPath), WINDOWS_FAST_TIMEOUT);
        }
        List<String> systems = detectBuildSystems(state.root);
        return Map.of("detectedBuildSystems", systems, "buildCommand", defaultBuildCommand(state.root, systems));
    }

    @Override
    public CommandResult buildRun(String workspaceId, BuildRunRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        String command = request == null ? "" : request.command();
        if (blank(command)) {
            command = state.workspace.buildCommand();
        }
        return runCommand(workspaceId, new CommandRequest(command,
                request == null ? 0 : request.timeoutSeconds(),
                request == null ? 0 : request.maxOutputCharacters()));
    }

    @Override
    public CommandResult testRun(String workspaceId, BuildRunRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        String command = request == null ? "" : request.command();
        if (blank(command)) {
            command = state.workspace.testCommand();
        }
        return runCommand(workspaceId, new CommandRequest(command,
                request == null ? 0 : request.timeoutSeconds(),
                request == null ? 0 : request.maxOutputCharacters()));
    }

    @Override
    public CodingTask startTask(StartTaskRequest request) {
        if (request == null || blank(request.workspaceId())) {
            throw new IllegalArgumentException("Workspace id is required.");
        }
        WorkspaceState state = requireWorkspace(request.workspaceId());
        String id = UUID.randomUUID().toString();
        List<PlanStep> plan = List.of(
                new PlanStep("analyze", "Analyze registered workspace", PlanStepStatus.COMPLETED),
                new PlanStep("plan", "Prepare implementation plan", PlanStepStatus.COMPLETED),
                new PlanStep("verify", "Run explicit build or test command from the Coding tab", PlanStepStatus.PENDING)
        );
        GitSnapshot git = gitSnapshot(state.workspace.id());
        CodingTask task = new CodingTask(
                id,
                state.workspace.id(),
                request.conversationId(),
                request.model(),
                request.prompt(),
                CodingTaskStatus.PLANNING,
                plan,
                "Workspace registered and ready for tool-backed coding operations.",
                1,
                Instant.now(),
                null,
                Map.of("gitStatus", git.status()),
                "",
                "",
                ""
        );
        tasks.put(id, task);
        return task;
    }

    @Override
    public CodingTask task(String taskId) {
        CodingTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown coding task: " + taskId);
        }
        return task;
    }

    @Override
    public List<CodingTask> tasks() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(CodingTask::startedAt, Comparator.reverseOrder()))
                .toList();
    }

    private CodingWorkspace inspectWorkspace(
            String id,
            Path root,
            String name,
            String projectType,
            AutonomyLevel autonomy,
            String buildCommand,
            String testCommand
    ) {
        List<String> buildSystems = detectBuildSystems(root);
        String detectedBuild = blank(buildCommand) ? defaultBuildCommand(root, buildSystems) : buildCommand;
        String detectedTest = blank(testCommand) ? defaultTestCommand(root, buildSystems) : testCommand;
        boolean gitRepository = Files.isDirectory(root.resolve(".git"));
        return new CodingWorkspace(
                id,
                name,
                root.toString(),
                CodingService.WorkspaceHost.SERVER,
                "AUTO".equals(projectType) ? String.join(",", buildSystems) : projectType,
                buildSystems,
                gitRepository,
                gitRepository ? gitLine(root, "rev-parse", "--abbrev-ref", "HEAD") : "",
                gitRepository ? gitLine(root, "rev-parse", "HEAD") : "",
                gitRepository ? gitText(root, "status", "--short") : "",
                autonomy,
                detectedBuild,
                detectedTest,
                Instant.now()
        );
    }

    private CodingWorkspace inspectWindowsWorkspace(
            String id,
            String windowsPath,
            String name,
            String projectType,
            AutonomyLevel autonomy,
            String buildCommand,
            String testCommand
    ) {
        Map<String, Object> response = windowsRequest("workspace_inspect", Map.of("rootPath", windowsPath), WINDOWS_FAST_TIMEOUT);
        List<String> buildSystems = strings(response.get("detectedBuildSystems"));
        String detectedBuild = blank(buildCommand) ? string(response, "buildCommand", "") : buildCommand;
        String detectedTest = blank(testCommand) ? string(response, "testCommand", "") : testCommand;
        return new CodingWorkspace(
                id,
                blank(name) ? string(response, "name", windowsPath) : name,
                string(response, "canonicalPath", windowsPath),
                CodingService.WorkspaceHost.WINDOWS,
                "AUTO".equals(projectType) ? String.join(",", buildSystems) : projectType,
                buildSystems,
                bool(response, "gitRepository"),
                string(response, "gitBranch", ""),
                string(response, "gitHeadCommit", ""),
                string(response, "gitStatus", ""),
                autonomy,
                detectedBuild,
                detectedTest,
                Instant.now()
        );
    }

    private List<String> detectBuildSystems(Path root) {
        List<String> systems = new ArrayList<>();
        if (Files.exists(root.resolve("mvnw.cmd")) || Files.exists(root.resolve("mvnw")) || Files.exists(root.resolve("pom.xml"))) {
            systems.add("Maven");
        }
        if (Files.exists(root.resolve("gradlew.bat")) || Files.exists(root.resolve("gradlew")) || Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
            systems.add("Gradle");
        }
        if (Files.exists(root.resolve("package.json"))) {
            systems.add("npm");
        }
        if (Files.exists(root.resolve("pnpm-lock.yaml"))) {
            systems.add("pnpm");
        }
        if (Files.exists(root.resolve("yarn.lock"))) {
            systems.add("yarn");
        }
        if (Files.exists(root.resolve("Cargo.toml"))) {
            systems.add("Cargo");
        }
        if (Files.exists(root.resolve("pytest.ini")) || Files.exists(root.resolve("pyproject.toml"))) {
            systems.add("Python/pytest");
        }
        if (systems.isEmpty()) {
            systems.add("Unknown");
        }
        return List.copyOf(systems);
    }

    private String defaultBuildCommand(Path root, List<String> systems) {
        if (systems.contains("Maven")) {
            return Files.exists(root.resolve("mvnw.cmd")) ? ".\\mvnw.cmd test" : "mvn test";
        }
        if (systems.contains("Gradle")) {
            return Files.exists(root.resolve("gradlew.bat")) ? ".\\gradlew.bat build" : "gradle build";
        }
        if (systems.contains("pnpm")) {
            return "pnpm test";
        }
        if (systems.contains("yarn")) {
            return "yarn test";
        }
        if (systems.contains("npm")) {
            return "npm test";
        }
        if (systems.contains("Cargo")) {
            return "cargo test";
        }
        if (systems.contains("Python/pytest")) {
            return "pytest";
        }
        return "";
    }

    private String defaultTestCommand(Path root, List<String> systems) {
        return defaultBuildCommand(root, systems);
    }

    private WorkspaceState requireWorkspace(String workspaceId) {
        WorkspaceState state = workspaces.get(workspaceId);
        if (state == null) {
            throw new IllegalArgumentException("Unknown coding workspace: " + workspaceId);
        }
        return state;
    }

    private void requireWriteAllowed(WorkspaceState state) {
        if (state.workspace.autonomyLevel() == AutonomyLevel.READ_ONLY) {
            throw new IllegalStateException("Workspace is READ_ONLY; writes require a higher autonomy level.");
        }
    }

    private void requireCommandAllowed(WorkspaceState state, String command) {
        if (state.workspace.autonomyLevel() == AutonomyLevel.READ_ONLY) {
            throw new IllegalStateException("Workspace is READ_ONLY; command execution is disabled.");
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        List<String> blocked = List.of(
                "git reset",
                "git clean",
                "git checkout",
                "git push",
                "git commit",
                "git merge",
                "git rebase",
                "rm ",
                "del ",
                "rmdir ",
                "remove-item"
        );
        if (blocked.stream().anyMatch(normalized::contains)) {
            throw new IllegalStateException("Command requires explicit approval outside the Coding Agent API: " + command);
        }
    }

    private Path resolveInsideWorkspace(WorkspaceState state, String path) {
        Path resolved = state.root.resolve(safeRelativePath(path)).normalize();
        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException exception) {
            if (!resolved.toAbsolutePath().normalize().startsWith(state.root)) {
                throw new IllegalArgumentException("Path escapes the registered coding workspace.");
            }
            throw new IllegalArgumentException("Path does not exist inside workspace: " + path);
        }
        if (!real.startsWith(state.root)) {
            throw new IllegalArgumentException("Path escapes the registered coding workspace.");
        }
        return real;
    }

    private Path resolveCreatableInsideWorkspace(WorkspaceState state, String path) {
        Path resolved = state.root.resolve(safeRelativePath(path)).normalize();
        Path parent = resolved.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        try {
            Path realParent = Files.exists(parent) ? parent.toRealPath() : parent.toAbsolutePath().normalize();
            if (!realParent.startsWith(state.root)) {
                throw new IllegalArgumentException("Path escapes the registered coding workspace.");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid path: " + path, exception);
        }
        return resolved;
    }

    private Path safeRelativePath(String rawPath) {
        String normalized = rawPath == null ? "" : rawPath.replace('\\', '/');
        Path path = Path.of(normalized).normalize();
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Absolute paths are not allowed inside a coding workspace operation.");
        }
        return path;
    }

    private FileContent readResolvedFile(WorkspaceState state, Path file, Integer startLine, Integer endLine) {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Path is not a file: " + relative(state, file));
        }
        if (size(file) > MAX_READ_BYTES) {
            throw new IllegalArgumentException("File is too large to read through Coding Agent API.");
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int first = startLine == null || startLine < 1 ? 1 : startLine;
            int last = endLine == null || endLine < first ? lines.size() : Math.min(endLine, lines.size());
            int from = Math.min(Math.max(0, first - 1), lines.size());
            int to = Math.min(Math.max(from, last), lines.size());
            String content = String.join(System.lineSeparator(), lines.subList(from, to));
            return new FileContent(relative(state, file), first, last, content, sha256(Files.readAllBytes(file)));
        } catch (IOException exception) {
            throw new IllegalStateException("File read failed: " + exception.getMessage(), exception);
        }
    }

    private CodingWorkspace touch(CodingWorkspace workspace) {
        CodingWorkspace touched = new CodingWorkspace(
                workspace.id(),
                workspace.name(),
                workspace.windowsPath(),
                workspace.host(),
                workspace.projectType(),
                workspace.detectedBuildSystems(),
                workspace.gitRepository(),
                workspace.gitBranch(),
                workspace.gitHeadCommit(),
                workspace.gitStatus(),
                workspace.autonomyLevel(),
                workspace.buildCommand(),
                workspace.testCommand(),
                Instant.now()
        );
        workspaces.computeIfPresent(workspace.id(), (id, state) -> {
            state.workspace = touched;
            return state;
        });
        return touched;
    }

    private Path canonicalExistingDirectory(Path path) {
        try {
            Path real = path.toRealPath();
            if (!Files.isDirectory(real)) {
                throw new IllegalArgumentException("Workspace path is not a directory: " + path);
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Workspace path does not exist: " + path, exception);
        }
    }

    private String gitLine(Path root, String... args) {
        return firstLine(gitText(root, args));
    }

    private String gitText(Path root, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.exitValue() == 0 ? stdout.stripTrailing() : "";
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "";
        }
    }

    private ProcessBuilder shellCommand(String command) {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("sh", "-lc", command);
    }

    private boolean isIgnored(Path path) {
        String value = path.toString().replace('\\', '/');
        return value.contains("/.git/") || value.contains("/target/") || value.contains("/node_modules/");
    }

    private String relative(WorkspaceState state, Path path) {
        return state.root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private long size(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable.", exception);
        }
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text.trim() : text.substring(0, newline).trim();
    }

    private String trim(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> windowsRequest(String operation, Map<String, Object> payload, java.time.Duration timeout) {
        if (windowsBridgeGateway == null) {
            throw new IllegalStateException("Windows Coding Executor is unavailable: Windows Bridge is not configured.");
        }
        try {
            return windowsBridgeGateway.codingRequest(operation, payload, timeout);
        } catch (McpException exception) {
            throw new IllegalStateException(exception.getMessage(), exception);
        }
    }

    private FileContent fileContent(Map<String, Object> response) {
        return new FileContent(
                string(response, "path", ""),
                (int) longValue(response, "startLine", 1),
                (int) longValue(response, "endLine", 1),
                string(response, "content", ""),
                string(response, "sha256", "")
        );
    }

    private CommandResult commandResult(Map<String, Object> response, String fallbackCommand) {
        return new CommandResult(
                string(response, "processId", ""),
                string(response, "command", fallbackCommand),
                (int) longValue(response, "exitCode", -1),
                bool(response, "timedOut"),
                string(response, "stdout", ""),
                string(response, "stderr", "")
        );
    }

    private List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> converted = new LinkedHashMap<>();
                map.forEach((key, element) -> converted.put(String.valueOf(key), element));
                result.add(converted);
            }
        }
        return List.copyOf(result);
    }

    private List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of("Unknown");
        }
        List<String> result = list.stream()
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
        return result.isEmpty() ? List.of("Unknown") : result;
    }

    private String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private long longValue(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static final class WorkspaceState {
        private final String id;
        private final Path root;
        private final String windowsPath;
        private final CodingService.WorkspaceHost host;
        private CodingWorkspace workspace;

        private WorkspaceState(String id, Path root, String windowsPath, CodingService.WorkspaceHost host, CodingWorkspace workspace) {
            this.id = id;
            this.root = root;
            this.windowsPath = windowsPath;
            this.host = host;
            this.workspace = workspace;
        }
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream inputStream;
        private final int maxCharacters;
        private final StringBuilder content = new StringBuilder();

        private StreamCollector(InputStream inputStream, int maxCharacters) {
            this.inputStream = inputStream;
            this.maxCharacters = maxCharacters;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (content.length() < maxCharacters) {
                        int remaining = maxCharacters - content.length();
                        content.append(line, 0, Math.min(line.length(), remaining)).append(System.lineSeparator());
                    }
                }
            } catch (IOException ignored) {
                // Process streams can close while a timed-out command is being destroyed.
            }
        }

        private String content() {
            return content.toString();
        }
    }
}
