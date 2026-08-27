package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
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

    private final Map<String, WorkspaceState> workspaces = new ConcurrentHashMap<>();
    private final Map<String, CodingTask> tasks = new ConcurrentHashMap<>();

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
        Path root = canonicalExistingDirectory(Path.of(request.windowsPath()));
        String id = UUID.randomUUID().toString();
        AutonomyLevel autonomy = request.autonomyLevel() == null ? AutonomyLevel.ASK_BEFORE_WRITE : request.autonomyLevel();
        CodingWorkspace workspace = inspectWorkspace(
                id,
                root,
                blank(request.name()) ? root.getFileName().toString() : request.name(),
                blank(request.projectType()) ? "AUTO" : request.projectType(),
                autonomy,
                request.buildCommand(),
                request.testCommand()
        );
        workspaces.put(id, new WorkspaceState(id, root, workspace));
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
        state.workspace = inspectWorkspace(
                current.id(),
                state.root,
                current.name(),
                current.projectType(),
                current.autonomyLevel(),
                current.buildCommand(),
                current.testCommand()
        );
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
    public CommandResult runCommand(String workspaceId, CommandRequest request) {
        WorkspaceState state = requireWorkspace(workspaceId);
        if (request == null || blank(request.command())) {
            throw new IllegalArgumentException("Command is required.");
        }
        requireCommandAllowed(state, request.command());
        long timeoutSeconds = request.timeoutSeconds() <= 0 ? 60 : Math.min(request.timeoutSeconds(), 900);
        int maxOutput = request.maxOutputCharacters() <= 0 ? DEFAULT_MAX_OUTPUT : Math.min(request.maxOutputCharacters(), 250_000);
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
    public GitSnapshot gitSnapshot(String workspaceId) {
        WorkspaceState state = requireWorkspace(workspaceId);
        return new GitSnapshot(
                gitLine(state.root, "rev-parse", "--abbrev-ref", "HEAD"),
                gitLine(state.root, "rev-parse", "HEAD"),
                gitText(state.root, "status", "--short"),
                gitText(state.root, "diff", "--", ".")
        );
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
        Path resolved = state.root.resolve(path == null ? "" : path).normalize();
        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Path does not exist inside workspace: " + path);
        }
        if (!real.startsWith(state.root)) {
            throw new IllegalArgumentException("Path escapes the registered coding workspace.");
        }
        return real;
    }

    private Path resolveCreatableInsideWorkspace(WorkspaceState state, String path) {
        Path resolved = state.root.resolve(path).normalize();
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

    private static final class WorkspaceState {
        private final String id;
        private final Path root;
        private CodingWorkspace workspace;

        private WorkspaceState(String id, Path root, CodingWorkspace workspace) {
            this.id = id;
            this.root = root;
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
