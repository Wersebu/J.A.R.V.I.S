package com.jarvis.core.coding;

import com.jarvis.brain.BrainRouter;
import com.jarvis.api.service.CodingService;
import com.jarvis.api.service.WindowsCodingBridgeGateway;
import com.jarvis.common.ai.Brain;
import com.jarvis.common.ai.BrainType;
import com.jarvis.common.ai.ReasoningLevel;
import com.jarvis.common.dto.ChatRequest;
import com.jarvis.common.event.CognitiveEventBus;
import com.jarvis.common.event.CognitiveEventType;
import com.jarvis.common.knowledge.KnowledgeMode;
import com.jarvis.tools.mcp.McpException;
import com.jarvis.tools.ToolResult;
import com.jarvis.tools.runtime.ToolCallingRequest;
import com.jarvis.tools.runtime.ToolCallingResult;
import com.jarvis.tools.runtime.ToolCallingRuntime;
import com.jarvis.tools.runtime.ToolLoopTerminationInfo;
import com.jarvis.tools.runtime.ToolRuntimeStep;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
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
    private final CodingTaskRepository taskRepository;
    private final WindowsCodingBridgeGateway windowsBridgeGateway;
    private final Supplier<ToolCallingRuntime> toolCallingRuntime;
    private final BrainRouter brainRouter;
    private final CognitiveEventBus cognitiveEventBus;
    private final Executor taskExecutor;

    public DefaultCodingService() {
        this(null, new InMemoryCodingTaskRepository(), null, null, null, Executors.newVirtualThreadPerTaskExecutor());
    }

    @Autowired
    public DefaultCodingService(
            ObjectProvider<WindowsCodingBridgeGateway> windowsBridgeGateway,
            ObjectProvider<CodingTaskRepository> taskRepository,
            ObjectProvider<ToolCallingRuntime> toolCallingRuntime,
            ObjectProvider<BrainRouter> brainRouter,
            ObjectProvider<CognitiveEventBus> cognitiveEventBus
    ) {
        this(
                windowsBridgeGateway.getIfAvailable(),
                taskRepository.getIfAvailable(InMemoryCodingTaskRepository::new),
                toolCallingRuntime::getIfAvailable,
                brainRouter.getIfAvailable(),
                cognitiveEventBus.getIfAvailable(),
                Executors.newVirtualThreadPerTaskExecutor()
        );
    }

    DefaultCodingService(WindowsCodingBridgeGateway windowsBridgeGateway) {
        this(windowsBridgeGateway, new InMemoryCodingTaskRepository(), null, null, null, Executors.newVirtualThreadPerTaskExecutor());
    }

    DefaultCodingService(
            WindowsCodingBridgeGateway windowsBridgeGateway,
            CodingTaskRepository taskRepository,
            Supplier<ToolCallingRuntime> toolCallingRuntime,
            BrainRouter brainRouter,
            CognitiveEventBus cognitiveEventBus,
            Executor taskExecutor
    ) {
        this.windowsBridgeGateway = windowsBridgeGateway;
        this.taskRepository = taskRepository == null ? new InMemoryCodingTaskRepository() : taskRepository;
        this.toolCallingRuntime = toolCallingRuntime == null ? () -> null : toolCallingRuntime;
        this.brainRouter = brainRouter;
        this.cognitiveEventBus = cognitiveEventBus;
        this.taskExecutor = taskExecutor == null ? Executors.newVirtualThreadPerTaskExecutor() : taskExecutor;
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
        AutonomyLevel autonomy = request.autonomyLevel() == null ? AutonomyLevel.EDIT_AND_TEST : request.autonomyLevel();
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
        if (!Files.isDirectory(state.root.resolve(".git"))) {
            return new GitSnapshot("", "", "", "");
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
        CodingTask task = new CodingTask(
                id,
                state.workspace.id(),
                request.conversationId(),
                request.model(),
                request.prompt(),
                CodingTaskStatus.CREATED,
                initialPlan(),
                "Coding task created.",
                0,
                Instant.now(),
                null,
                Map.of(),
                "",
                "",
                ""
        );
        taskRepository.save(task);
        publishTaskEvent(task, "CREATED", "Coding task accepted for asynchronous execution", Map.of());
        taskExecutor.execute(() -> runTaskLoop(id, state.id));
        return task;
    }

    @Override
    public CodingTask task(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown coding task: " + taskId));
    }

    @Override
    public List<CodingTask> tasks() {
        return taskRepository.findAll();
    }

    private List<PlanStep> initialPlan() {
        return List.of(
                new PlanStep("inspect", "Inspect workspace, project instructions and build system", PlanStepStatus.PENDING),
                new PlanStep("snapshot", "Capture initial Git status and diff", PlanStepStatus.PENDING),
                new PlanStep("plan", "Prepare a bounded coding plan from the task and workspace evidence", PlanStepStatus.PENDING),
                new PlanStep("verify", "Run the detected or configured test/build command", PlanStepStatus.PENDING),
                new PlanStep("analyze", "Analyze command output and decide whether another iteration is needed", PlanStepStatus.PENDING),
                new PlanStep("final", "Capture final Git status/diff and report the outcome", PlanStepStatus.PENDING)
        );
    }

    private void runTaskLoop(String taskId, String workspaceId) {
        CodingTask task = task(taskId);
        WorkspaceState state = requireWorkspace(workspaceId);
        try {
            ToolCallingRuntime runtime = toolCallingRuntime.get();
            CodingTask finished = runtime == null ? runLegacyTaskLoop(task, state) : runNativeModelTaskLoop(task, state, runtime);
            taskRepository.save(finished);
        } catch (RuntimeException exception) {
            String failureReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            updateTask(task(taskId), CodingTaskStatus.FAILED, fail(task(taskId).plan(), "final"), failureReason, 0,
                    Map.of(), new StringBuilder(), new StringBuilder(), failureReason, Instant.now());
        }
    }

    private CodingTask runNativeModelTaskLoop(CodingTask task, WorkspaceState state, ToolCallingRuntime runtime) {
        Map<String, String> changedFiles = new LinkedHashMap<>();
        StringBuilder buildResult = new StringBuilder();
        StringBuilder testResult = new StringBuilder();
        String failureReason = "";
        int iteration = 0;
        try {
            iteration++;
            task = updateTask(task, CodingTaskStatus.INSPECTING, complete(task.plan(), "inspect"),
                    "Inspecting workspace before model loop.", iteration, changedFiles, buildResult, testResult, failureReason, null);
            CodingWorkspace workspace = refreshWorkspace(state.workspace.id());
            Map<String, Object> build = buildDetect(workspace.id());
            List<String> instructionFiles = discoverInstructionFiles(workspace.id());
            GitSnapshot initialGit = gitSnapshot(workspace.id());
            changedFiles.put("workspace", workspace.windowsPath());
            changedFiles.put("buildSystems", String.join(",", workspace.detectedBuildSystems()));
            changedFiles.put("instructions", String.join(",", instructionFiles));
            changedFiles.put("initialGitStatus", trim(initialGit.status(), 8_000));
            changedFiles.put("initialGitDiffSha256", sha256(initialGit.diff().getBytes(StandardCharsets.UTF_8)));
            task = updateTask(task, CodingTaskStatus.PLANNING, complete(complete(task.plan(), "snapshot"), "plan"),
                    "Starting model-owned Coding Agent loop.", iteration, changedFiles, buildResult, testResult, failureReason, null);

            ToolCallingResult result = runtime.execute(new ToolCallingRequest(
                    "coding-task-" + task.id(),
                    blank(task.conversationId()) ? "coding-task-" + task.id() : task.conversationId(),
                    task.prompt(),
                    codingGoal(task, workspace, build, instructionFiles),
                    "Run a real model-owned Coding Agent loop against the active workspace. Inspect, edit only when needed, test, feed failures back to the model, and finish with git diff evidence.",
                    Map.of(
                            "activeCodingWorkspaceId", workspace.id(),
                            "activeCodingWorkspaceName", workspace.name(),
                            "activeCodingWorkspaceHost", workspace.host().name(),
                            "codingTaskId", task.id(),
                            "userId", ""
                    ),
                    codingAgentSystemPrompt(workspace, build, instructionFiles),
                    selectCodingBrain(task, workspace),
                    KnowledgeMode.FAST,
                    List.of(),
                    ""
            ));

            ToolLoopTerminationInfo termination = result.terminationInfo();
            appendRuntimeResults(buildResult, testResult, result);
            GitSnapshot finalGit = gitSnapshot(workspace.id());
            changedFiles.put("finalGitStatus", trim(finalGit.status(), 8_000));
            changedFiles.put("finalGitDiffSha256", sha256(finalGit.diff().getBytes(StandardCharsets.UTF_8)));
            changedFiles.put("gitDiffChanged", String.valueOf(!initialGit.diff().equals(finalGit.diff())));
            changedFiles.put("modelTurns", String.valueOf(termination.usedModelTurns()));
            changedFiles.put("toolCalls", String.valueOf(termination.executedToolCalls()));
            changedFiles.put("toolCallOrder", toolCallOrder(result.steps()));

            List<PlanStep> completedPlan = complete(complete(complete(task.plan(), "verify"), "analyze"), "final");
            CodingTaskStatus status = termination.completed()
                    ? CodingTaskStatus.COMPLETED
                    : termination.terminationReason() == com.jarvis.tools.runtime.ToolLoopTerminationReason.WAITING_FOR_APPROVAL
                    ? CodingTaskStatus.WAITING_FOR_APPROVAL
                    : CodingTaskStatus.FAILED;
            failureReason = status == CodingTaskStatus.COMPLETED ? "" : runtimeFailureReason(termination, result);
            return updateTask(task, status, completedPlan, finalAction(result, termination), termination.usedModelTurns(),
                    changedFiles, buildResult, testResult, failureReason, Instant.now());
        } catch (RuntimeException exception) {
            failureReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return updateTask(task, CodingTaskStatus.FAILED, fail(task.plan(), "final"), failureReason, iteration,
                    changedFiles, buildResult, testResult, failureReason, Instant.now());
        }
    }

    private CodingTask runLegacyTaskLoop(CodingTask task, WorkspaceState state) {
        Map<String, String> changedFiles = new LinkedHashMap<>();
        StringBuilder buildResult = new StringBuilder();
        StringBuilder testResult = new StringBuilder();
        String failureReason = "";
        int iteration = 0;
        List<PlanStep> plan = task.plan();
        GitSnapshot initialGit = new GitSnapshot("", "", "", "");
        GitSnapshot finalGit = new GitSnapshot("", "", "", "");
        try {
            iteration++;
            task = updateTask(task, CodingTaskStatus.INSPECTING, complete(plan, "inspect"), "Inspecting workspace.", iteration,
                    changedFiles, buildResult, testResult, failureReason, null);
            CodingWorkspace workspace = refreshWorkspace(state.workspace.id());
            Map<String, Object> build = buildDetect(workspace.id());
            List<String> instructionFiles = discoverInstructionFiles(workspace.id());
            changedFiles.put("workspace", workspace.windowsPath());
            changedFiles.put("buildSystems", String.join(",", workspace.detectedBuildSystems()));
            changedFiles.put("instructions", String.join(",", instructionFiles));

            task = updateTask(task, CodingTaskStatus.ANALYZING, complete(task.plan(), "snapshot"),
                    "Capturing initial Git snapshot.", iteration, changedFiles, buildResult, testResult, failureReason, null);
            initialGit = gitSnapshot(workspace.id());
            changedFiles.put("initialGitStatus", trim(initialGit.status(), 8_000));
            changedFiles.put("initialGitDiffSha256", sha256(initialGit.diff().getBytes(StandardCharsets.UTF_8)));

            task = updateTask(task, CodingTaskStatus.PLANNING, complete(task.plan(), "plan"),
                    "Prepared bounded plan from workspace inspection.", iteration, changedFiles, buildResult, testResult, failureReason, null);
            String command = testCommand(workspace, build);
            if (blank(command)) {
                failureReason = "No build or test command could be detected for this workspace.";
                finalGit = gitSnapshot(workspace.id());
                changedFiles.put("finalGitStatus", trim(finalGit.status(), 8_000));
                changedFiles.put("finalGitDiffSha256", sha256(finalGit.diff().getBytes(StandardCharsets.UTF_8)));
                return updateTask(task, CodingTaskStatus.FAILED, fail(task.plan(), "verify"), failureReason, iteration,
                        changedFiles, buildResult, testResult, failureReason, Instant.now());
            }
            if (workspace.autonomyLevel() == AutonomyLevel.READ_ONLY) {
                failureReason = "Verification command requires EDIT_AND_TEST or FULL_WITH_APPROVALS autonomy.";
                return updateTask(task, CodingTaskStatus.WAITING_FOR_APPROVAL, fail(task.plan(), "verify"), failureReason,
                        iteration, changedFiles, buildResult, testResult, failureReason, null);
            }

            task = updateTask(task, CodingTaskStatus.TESTING, complete(task.plan(), "verify"),
                    "Running verification command: " + command, iteration, changedFiles, buildResult, testResult, failureReason, null);
            CommandResult verification = runCommand(workspace.id(), new CommandRequest(command, 0, DEFAULT_MAX_OUTPUT));
            appendCommandResult(testResult, verification);

            task = updateTask(task, CodingTaskStatus.ANALYZING_RESULT, complete(task.plan(), "analyze"),
                    "Analyzing verification result.", iteration, changedFiles, buildResult, testResult, failureReason, null);
            finalGit = gitSnapshot(workspace.id());
            changedFiles.put("finalGitStatus", trim(finalGit.status(), 8_000));
            changedFiles.put("finalGitDiffSha256", sha256(finalGit.diff().getBytes(StandardCharsets.UTF_8)));
            changedFiles.put("gitDiffChanged", String.valueOf(!initialGit.diff().equals(finalGit.diff())));

            if (verification.exitCode() == 0 && !verification.timedOut()) {
                return updateTask(task, CodingTaskStatus.COMPLETED, complete(task.plan(), "final"),
                        "Verification succeeded. Final Git snapshot captured.", iteration, changedFiles,
                        buildResult, testResult, "", Instant.now());
            }
            failureReason = verification.timedOut()
                    ? "Verification command timed out."
                    : "Verification command failed with exit code " + verification.exitCode() + ".";
            return updateTask(task, CodingTaskStatus.FAILED, fail(task.plan(), "final"), failureReason, iteration,
                    changedFiles, buildResult, testResult, failureReason, Instant.now());
        } catch (RuntimeException exception) {
            failureReason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            return updateTask(task, CodingTaskStatus.FAILED, fail(task.plan(), "final"), failureReason, iteration,
                    changedFiles, buildResult, testResult, failureReason, Instant.now());
        }
    }

    private CodingTask updateTask(
            CodingTask task,
            CodingTaskStatus status,
            List<PlanStep> plan,
            String currentAction,
            int iteration,
            Map<String, String> changedFiles,
            StringBuilder buildResult,
            StringBuilder testResult,
            String failureReason,
            Instant finishedAt
    ) {
        CodingTask updated = new CodingTask(
                task.id(),
                task.workspaceId(),
                task.conversationId(),
                task.model(),
                task.prompt(),
                status,
                plan,
                currentAction,
                iteration,
                task.startedAt(),
                finishedAt,
                Map.copyOf(changedFiles),
                buildResult.toString(),
                testResult.toString(),
                failureReason == null ? "" : failureReason
        );
        taskRepository.save(updated);
        publishTaskEvent(updated, status.name(), currentAction, Map.of(
                "iteration", iteration,
                "finished", finishedAt != null
        ));
        return updated;
    }

    private String codingGoal(CodingTask task, CodingWorkspace workspace, Map<String, Object> build, List<String> instructionFiles) {
        return """
                Complete this Coding Agent task inside the active workspace.

                User request:
                %s

                Workspace:
                - id: %s
                - name: %s
                - host: %s
                - path: %s
                - autonomy: %s
                - detected build systems: %s
                - build command: %s
                - test command: %s
                - instruction files already discovered: %s

                Build detection:
                %s
                """.formatted(
                task.prompt(),
                workspace.id(),
                workspace.name(),
                workspace.host(),
                workspace.windowsPath(),
                workspace.autonomyLevel(),
                workspace.detectedBuildSystems(),
                workspace.buildCommand(),
                workspace.testCommand(),
                instructionFiles,
                build
        );
    }

    private String codingAgentSystemPrompt(CodingWorkspace workspace, Map<String, Object> build, List<String> instructionFiles) {
        return """
                You are J.A.R.V.I.S Coding Agent running through native tool calls.
                Use only the coding tools for project files in the active Coding Workspace. Do not use Knowledge tools to read source files.
                The runtime injects the workspace id; never provide or invent a workspaceId argument.
                Work in a tight loop: inspect the workspace, read the relevant files, run the configured tests/build, use failing output as evidence, patch the smallest necessary change, rerun verification, inspect git diff, and then answer.
                Do not mark the task complete after the first failed test. A failing test is feedback for the next model turn.
                Do not commit, push, reset, clean, checkout, merge, or rebase.
                Obey workspace autonomy. READ_ONLY means analysis only and no command/write calls.

                Active workspace: %s (%s, %s)
                Build detection: %s
                Instruction files: %s
                """.formatted(workspace.name(), workspace.host(), workspace.windowsPath(), build, instructionFiles);
    }

    private Brain selectCodingBrain(CodingTask task, CodingWorkspace workspace) {
        if (brainRouter != null) {
            return brainRouter.select(new ChatRequest(
                    task.conversationId(),
                    task.prompt(),
                    task.startedAt(),
                    KnowledgeMode.FAST,
                    List.of(),
                    workspace.id(),
                    workspace.name(),
                    workspace.host().name()
            ));
        }
        String model = blank(task.model()) ? "coding" : task.model();
        return new Brain(BrainType.CODING, model, model, "Coding Agent", "Coding task fallback brain", 0L, ReasoningLevel.MEDIUM);
    }

    private void appendRuntimeResults(StringBuilder buildResult, StringBuilder testResult, ToolCallingResult result) {
        for (ToolResult toolResult : result.results()) {
            if (!"coding".equalsIgnoreCase(toolResult.tool())) {
                continue;
            }
            String operation = toolResult.operation().toUpperCase(Locale.ROOT);
            if (operation.equals("TEST_RUN")) {
                appendToolResult(testResult, toolResult);
            } else if (operation.equals("BUILD_RUN") || operation.equals("COMMAND_START")) {
                appendToolResult(buildResult, toolResult);
            }
        }
    }

    private void appendToolResult(StringBuilder target, ToolResult result) {
        target.append("tool=").append(result.tool()).append(" operation=").append(result.operation()).append(System.lineSeparator());
        target.append("success=").append(result.success()).append(System.lineSeparator());
        target.append("message=").append(result.message()).append(System.lineSeparator());
        target.append("data=").append(trim(String.valueOf(result.data()), DEFAULT_MAX_OUTPUT)).append(System.lineSeparator());
        if (!result.errorCode().isBlank()) {
            target.append("error=").append(result.errorCode()).append(": ").append(result.errorMessage()).append(System.lineSeparator());
        }
    }

    private String toolCallOrder(List<ToolRuntimeStep> steps) {
        return steps.stream()
                .filter(step -> !blank(step.tool()))
                .map(step -> step.tool() + "." + step.operation() + ":" + step.status())
                .toList()
                .toString();
    }

    private String finalAction(ToolCallingResult result, ToolLoopTerminationInfo termination) {
        if (!termination.lastModelContent().isBlank()) {
            return trim(termination.lastModelContent(), 2_000);
        }
        if (!result.finalAnswer().isBlank()) {
            return trim(result.finalAnswer(), 2_000);
        }
        return "Native Coding Agent loop finished: " + termination.terminationReason();
    }

    private String runtimeFailureReason(ToolLoopTerminationInfo termination, ToolCallingResult result) {
        if (!termination.lastErrorMessage().isBlank()) {
            return termination.lastErrorMessage();
        }
        if (!termination.nextRequiredAction().isBlank()) {
            return termination.nextRequiredAction();
        }
        if (!result.finalAnswer().isBlank()) {
            return trim(result.finalAnswer(), 2_000);
        }
        return "Native Coding Agent loop stopped with reason: " + termination.terminationReason();
    }

    private void publishTaskEvent(CodingTask task, String status, String message, Map<String, Object> metadata) {
        if (cognitiveEventBus == null) {
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>(metadata);
        values.put("taskId", task.id());
        values.put("workspaceId", task.workspaceId());
        values.put("conversationId", task.conversationId() == null ? "" : task.conversationId());
        values.put("status", status);
        cognitiveEventBus.publish(CognitiveEventType.EXECUTION_TRACE, status, message, "coding-task:" + task.id(), values);
    }

    private List<PlanStep> complete(List<PlanStep> plan, String id) {
        return updatePlanStep(plan, id, PlanStepStatus.COMPLETED);
    }

    private List<PlanStep> fail(List<PlanStep> plan, String id) {
        return updatePlanStep(plan, id, PlanStepStatus.FAILED);
    }

    private List<PlanStep> updatePlanStep(List<PlanStep> plan, String id, PlanStepStatus status) {
        return plan.stream()
                .map(step -> step.id().equals(id) ? new PlanStep(step.id(), step.title(), status) : step)
                .toList();
    }

    private List<String> discoverInstructionFiles(String workspaceId) {
        List<String> candidates = List.of("AGENTS.md", "README.md", "README.txt");
        List<String> found = new ArrayList<>();
        for (String candidate : candidates) {
            try {
                FileContent content = readFile(workspaceId, candidate, 1, 80);
                if (!content.content().isBlank()) {
                    found.add(candidate);
                }
            } catch (RuntimeException ignored) {
                // Missing instruction files are normal; the task record keeps the found list.
            }
        }
        return found;
    }

    private String testCommand(CodingWorkspace workspace, Map<String, Object> buildDetect) {
        if (!blank(workspace.testCommand())) {
            return workspace.testCommand();
        }
        Object detected = buildDetect.get("testCommand");
        if (detected != null && !String.valueOf(detected).isBlank()) {
            return String.valueOf(detected);
        }
        if (!blank(workspace.buildCommand())) {
            return workspace.buildCommand();
        }
        Object build = buildDetect.get("buildCommand");
        return build == null ? "" : String.valueOf(build);
    }

    private void appendCommandResult(StringBuilder target, CommandResult result) {
        target.append("command=").append(result.command()).append(System.lineSeparator());
        target.append("processId=").append(result.processId()).append(System.lineSeparator());
        target.append("exitCode=").append(result.exitCode()).append(System.lineSeparator());
        target.append("timedOut=").append(result.timedOut()).append(System.lineSeparator());
        target.append("stdout:").append(System.lineSeparator()).append(trim(result.stdout(), DEFAULT_MAX_OUTPUT)).append(System.lineSeparator());
        target.append("stderr:").append(System.lineSeparator()).append(trim(result.stderr(), DEFAULT_MAX_OUTPUT)).append(System.lineSeparator());
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
