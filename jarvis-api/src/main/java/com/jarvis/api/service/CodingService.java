package com.jarvis.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Durable coding workspace operations exposed to Windows clients.
 */
public interface CodingService {

    enum AutonomyLevel {
        READ_ONLY,
        EDIT_AND_TEST,
        FULL_WITH_APPROVALS,
        ASK_BEFORE_WRITE,
        AUTONOMOUS_IN_WORKSPACE
    }

    enum WorkspaceHost {
        WINDOWS,
        SERVER
    }

    enum PlanStepStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        BLOCKED,
        SKIPPED
    }

    enum CodingTaskStatus {
        QUEUED,
        WAITING_FOR_WORKER,
        STARTING,
        RUNNING,
        WAITING_FOR_USER,
        CREATED,
        IDLE,
        INSPECTING,
        ANALYZING,
        PLANNING,
        EXECUTING_TOOL,
        WAITING_FOR_TOOL,
        ANALYZING_RESULT,
        WAITING_FOR_APPROVAL,
        EDITING,
        RUNNING_COMMAND,
        BUILDING,
        TESTING,
        FIXING,
        COMPLETED,
        FAILED,
        TIMED_OUT,
        INTERRUPTED,
        WAITING_FOR_HOST,
        CANCELLED,
        BLOCKED
    }

    enum CodingApprovalStatus {
        PENDING,
        APPROVED,
        REJECTED,
        EXPIRED,
        CANCELLED,
        CONSUMED
    }

    record CodingRequestContext(String userId, String sessionId, String conversationId) {
        public CodingRequestContext {
            if (blank(userId)) {
                throw new IllegalArgumentException("Authenticated user id is required.");
            }
            sessionId = sessionId == null ? "" : sessionId;
            conversationId = conversationId == null ? "" : conversationId;
        }

        private static boolean blank(String value) {
            return value == null || value.isBlank();
        }
    }

    record RegisterWorkspaceRequest(
            String name,
            String windowsPath,
            WorkspaceHost host,
            String projectType,
            AutonomyLevel autonomyLevel,
            String buildCommand,
            String testCommand
    ) {
    }

    record CodingWorkspace(
            String id,
            String name,
            String windowsPath,
            WorkspaceHost host,
            String projectType,
            List<String> detectedBuildSystems,
            boolean gitRepository,
            String gitBranch,
            String gitHeadCommit,
            String gitStatus,
            AutonomyLevel autonomyLevel,
            String buildCommand,
            String testCommand,
            Instant lastUsedAt,
            String ownerUserId,
            Instant createdAt,
            Instant updatedAt
    ) {
        public CodingWorkspace {
            detectedBuildSystems = detectedBuildSystems == null ? List.of() : List.copyOf(detectedBuildSystems);
            lastUsedAt = lastUsedAt == null ? Instant.now() : lastUsedAt;
            ownerUserId = normalizeUserId(ownerUserId);
            createdAt = createdAt == null ? lastUsedAt : createdAt;
            updatedAt = updatedAt == null ? lastUsedAt : updatedAt;
        }

        public CodingWorkspace(
                String id,
                String name,
                String windowsPath,
                WorkspaceHost host,
                String projectType,
                List<String> detectedBuildSystems,
                boolean gitRepository,
                String gitBranch,
                String gitHeadCommit,
                String gitStatus,
                AutonomyLevel autonomyLevel,
                String buildCommand,
                String testCommand,
                Instant lastUsedAt
        ) {
            this(id, name, windowsPath, host, projectType, detectedBuildSystems, gitRepository, gitBranch,
                    gitHeadCommit, gitStatus, autonomyLevel, buildCommand, testCommand, lastUsedAt,
                    missingOwner(), lastUsedAt, lastUsedAt);
        }
    }

    record WorkspaceFileEntry(String path, boolean directory, long size) {
    }

    record FileContent(String path, int startLine, int endLine, String content, String sha256) {
    }

    record FileWriteRequest(String path, String content) {
    }

    record PatchRequest(String path, String expected, String replacement) {
    }

    record DirectoryCreateRequest(String path) {
    }

    record FileMoveRequest(String sourcePath, String targetPath) {
    }

    record FileDeleteRequest(String path, boolean approved) {
    }

    record FileSearchRequest(String query, boolean regex, int maxResults) {
    }

    record SearchMatch(String path, int line, String preview) {
    }

    record CommandRequest(String command, long timeoutSeconds, int maxOutputCharacters) {
    }

    record BuildRunRequest(String command, long timeoutSeconds, int maxOutputCharacters) {
    }

    record CommandResult(String processId, String command, int exitCode, boolean timedOut, String stdout, String stderr) {
    }

    record GitSnapshot(String branch, String headCommit, String status, String diff) {
    }

    record PlanStep(String id, String title, PlanStepStatus status) {
    }

    record CodingTask(
            String id,
            String workspaceId,
            String conversationId,
            String model,
            String prompt,
            CodingTaskStatus status,
            List<PlanStep> plan,
            String currentAction,
            int iteration,
            Instant startedAt,
            Instant finishedAt,
            Map<String, String> changedFiles,
            String buildResult,
            String testResult,
            String failureReason,
            String ownerUserId,
            Instant updatedAt,
            String finalAnswer,
            String systemPromptVersion,
            String openCodeSessionId,
            GitSnapshot initialGitSnapshot,
            GitSnapshot finalGitSnapshot
    ) {
        public CodingTask {
            plan = plan == null ? List.of() : List.copyOf(plan);
            startedAt = startedAt == null ? Instant.now() : startedAt;
            changedFiles = changedFiles == null ? Map.of() : Map.copyOf(changedFiles);
            ownerUserId = normalizeUserId(ownerUserId);
            updatedAt = updatedAt == null ? startedAt : updatedAt;
            finalAnswer = finalAnswer == null ? "" : finalAnswer;
            systemPromptVersion = systemPromptVersion == null ? "" : systemPromptVersion;
            openCodeSessionId = openCodeSessionId == null ? "" : openCodeSessionId;
            initialGitSnapshot = initialGitSnapshot == null ? new GitSnapshot("", "", "", "") : initialGitSnapshot;
            finalGitSnapshot = finalGitSnapshot == null ? new GitSnapshot("", "", "", "") : finalGitSnapshot;
        }

        public CodingTask(
                String id,
                String workspaceId,
                String conversationId,
                String model,
                String prompt,
                CodingTaskStatus status,
                List<PlanStep> plan,
                String currentAction,
                int iteration,
                Instant startedAt,
                Instant finishedAt,
                Map<String, String> changedFiles,
                String buildResult,
                String testResult,
                String failureReason
        ) {
            this(id, workspaceId, conversationId, model, prompt, status, plan, currentAction, iteration, startedAt,
                    finishedAt, changedFiles, buildResult, testResult, failureReason, missingOwner(), startedAt,
                    "", "", "", new GitSnapshot("", "", "", ""), new GitSnapshot("", "", "", ""));
        }
    }

    record StartTaskRequest(String workspaceId, String conversationId, String model, String prompt) {
    }

    record CodingReplyRequest(String message) {
    }

    record CodingApprovalDecisionRequest(String approvalId, String message) {
    }

    record CodingDiagnostics(
            boolean workerConnected,
            String openCodeStatus,
            String openCodeVersion,
            boolean projectDirectoryAvailable,
            boolean ollamaAvailable,
            boolean modelAvailable,
            String installationHint,
            String message
    ) {
    }

    record CodingApproval(
            String id,
            String taskId,
            String ownerUserId,
            String operation,
            String description,
            String riskLevel,
            String argumentsDigest,
            CodingApprovalStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant decidedAt,
            Instant consumedAt
    ) {
        public CodingApproval {
            ownerUserId = normalizeUserId(ownerUserId);
            status = status == null ? CodingApprovalStatus.PENDING : status;
            createdAt = createdAt == null ? Instant.now() : createdAt;
        }
    }

    List<CodingWorkspace> listWorkspaces();

    CodingWorkspace registerWorkspace(RegisterWorkspaceRequest request);

    CodingWorkspace workspace(String workspaceId);

    CodingWorkspace refreshWorkspace(String workspaceId);

    void removeWorkspace(String workspaceId);

    List<WorkspaceFileEntry> listFiles(String workspaceId, String path);

    FileContent readFile(String workspaceId, String path, Integer startLine, Integer endLine);

    List<SearchMatch> search(String workspaceId, FileSearchRequest request);

    FileContent writeFile(String workspaceId, FileWriteRequest request);

    FileContent patchFile(String workspaceId, PatchRequest request);

    WorkspaceFileEntry createDirectory(String workspaceId, DirectoryCreateRequest request);

    WorkspaceFileEntry moveFile(String workspaceId, FileMoveRequest request);

    void deleteFile(String workspaceId, FileDeleteRequest request);

    CommandResult runCommand(String workspaceId, CommandRequest request);

    CommandResult commandPoll(String workspaceId, String processId);

    CommandResult commandCancel(String workspaceId, String processId);

    GitSnapshot gitSnapshot(String workspaceId);

    Map<String, Object> buildDetect(String workspaceId);

    CommandResult buildRun(String workspaceId, BuildRunRequest request);

    CommandResult testRun(String workspaceId, BuildRunRequest request);

    CodingDiagnostics diagnostics(String workspaceId);

    CodingTask startTask(StartTaskRequest request);

    default CodingTask startTask(StartTaskRequest request, CodingRequestContext context) {
        return startTask(request);
    }

    default CodingTask cancelTask(String taskId, CodingRequestContext context) {
        throw new UnsupportedOperationException("Coding task cancellation is not implemented.");
    }

    default CodingTask reply(String taskId, CodingReplyRequest request, CodingRequestContext context) {
        throw new UnsupportedOperationException("Coding task replies are not implemented.");
    }

    default List<CodingApproval> approvals(String taskId) {
        throw new UnsupportedOperationException("Coding approvals are not implemented.");
    }

    default CodingApproval approve(String taskId, String approvalId, CodingRequestContext context) {
        throw new UnsupportedOperationException("Coding approvals are not implemented.");
    }

    default CodingApproval reject(String taskId, String approvalId, CodingRequestContext context) {
        throw new UnsupportedOperationException("Coding approvals are not implemented.");
    }

    default CodingApproval requestApproval(
            String taskId,
            String operation,
            String description,
            String riskLevel,
            String argumentsDigest
    ) {
        throw new UnsupportedOperationException("Coding approvals are not implemented.");
    }

    CodingTask task(String taskId);

    List<CodingTask> tasks();

    private static String normalizeUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("Explicit owner user id is required.");
        }
        return userId;
    }

    private static String missingOwner() {
        throw new IllegalArgumentException("Legacy Coding Agent constructors require an explicit owner user id.");
    }
}
