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
        CANCELLED,
        BLOCKED
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
            Instant lastUsedAt
    ) {
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
            String failureReason
    ) {
    }

    record StartTaskRequest(String workspaceId, String conversationId, String model, String prompt) {
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

    CodingTask startTask(StartTaskRequest request);

    CodingTask task(String taskId);

    List<CodingTask> tasks();
}
