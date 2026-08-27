package com.jarvis.api.controller;

import com.jarvis.api.service.CodingService;
import com.jarvis.api.service.CodingService.CodingTask;
import com.jarvis.api.service.CodingService.CodingWorkspace;
import com.jarvis.api.service.CodingService.CommandRequest;
import com.jarvis.api.service.CodingService.CommandResult;
import com.jarvis.api.service.CodingService.FileContent;
import com.jarvis.api.service.CodingService.FileSearchRequest;
import com.jarvis.api.service.CodingService.FileWriteRequest;
import com.jarvis.api.service.CodingService.GitSnapshot;
import com.jarvis.api.service.CodingService.PatchRequest;
import com.jarvis.api.service.CodingService.RegisterWorkspaceRequest;
import com.jarvis.api.service.CodingService.SearchMatch;
import com.jarvis.api.service.CodingService.StartTaskRequest;
import com.jarvis.api.service.CodingService.WorkspaceFileEntry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST API for controlled local coding workspaces.
 */
@RestController
public class CodingController {

    private final CodingService codingService;

    public CodingController(CodingService codingService) {
        this.codingService = codingService;
    }

    @GetMapping(path = "/api/v1/coding/workspaces", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<CodingWorkspace> listWorkspaces() {
        return codingService.listWorkspaces();
    }

    @PostMapping(path = "/api/v1/coding/workspaces", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CodingWorkspace registerWorkspace(@RequestBody RegisterWorkspaceRequest request) {
        return wrap(() -> codingService.registerWorkspace(request));
    }

    @GetMapping(path = "/api/v1/coding/workspaces/{workspaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CodingWorkspace workspace(@PathVariable String workspaceId) {
        return wrap(() -> codingService.workspace(workspaceId));
    }

    @PostMapping(path = "/api/v1/coding/workspaces/{workspaceId}/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public CodingWorkspace refreshWorkspace(@PathVariable String workspaceId) {
        return wrap(() -> codingService.refreshWorkspace(workspaceId));
    }

    @DeleteMapping(path = "/api/v1/coding/workspaces/{workspaceId}")
    public void removeWorkspace(@PathVariable String workspaceId) {
        wrapVoid(() -> codingService.removeWorkspace(workspaceId));
    }

    @GetMapping(path = "/api/v1/coding/workspaces/{workspaceId}/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<WorkspaceFileEntry> listFiles(@PathVariable String workspaceId, @RequestParam(defaultValue = "") String path) {
        return wrap(() -> codingService.listFiles(workspaceId, path));
    }

    @GetMapping(path = "/api/v1/coding/workspaces/{workspaceId}/files/read", produces = MediaType.APPLICATION_JSON_VALUE)
    public FileContent readFile(
            @PathVariable String workspaceId,
            @RequestParam String path,
            @RequestParam(required = false) Integer startLine,
            @RequestParam(required = false) Integer endLine
    ) {
        return wrap(() -> codingService.readFile(workspaceId, path, startLine, endLine));
    }

    @PostMapping(path = "/api/v1/coding/workspaces/{workspaceId}/files/search", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public List<SearchMatch> search(@PathVariable String workspaceId, @RequestBody FileSearchRequest request) {
        return wrap(() -> codingService.search(workspaceId, request));
    }

    @PostMapping(path = "/api/v1/coding/workspaces/{workspaceId}/files/write", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public FileContent writeFile(@PathVariable String workspaceId, @RequestBody FileWriteRequest request) {
        return wrap(() -> codingService.writeFile(workspaceId, request));
    }

    @PostMapping(path = "/api/v1/coding/workspaces/{workspaceId}/files/patch", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public FileContent patchFile(@PathVariable String workspaceId, @RequestBody PatchRequest request) {
        return wrap(() -> codingService.patchFile(workspaceId, request));
    }

    @PostMapping(path = "/api/v1/coding/workspaces/{workspaceId}/commands", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CommandResult runCommand(@PathVariable String workspaceId, @RequestBody CommandRequest request) {
        return wrap(() -> codingService.runCommand(workspaceId, request));
    }

    @GetMapping(path = "/api/v1/coding/workspaces/{workspaceId}/git", produces = MediaType.APPLICATION_JSON_VALUE)
    public GitSnapshot gitSnapshot(@PathVariable String workspaceId) {
        return wrap(() -> codingService.gitSnapshot(workspaceId));
    }

    @PostMapping(path = "/api/v1/coding/tasks", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CodingTask startTask(@RequestBody StartTaskRequest request) {
        return wrap(() -> codingService.startTask(request));
    }

    @GetMapping(path = "/api/v1/coding/tasks", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<CodingTask> tasks() {
        return codingService.tasks();
    }

    @GetMapping(path = "/api/v1/coding/tasks/{taskId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public CodingTask task(@PathVariable String taskId) {
        return wrap(() -> codingService.task(taskId));
    }

    private <T> T wrap(ControllerCall<T> call) {
        try {
            return call.execute();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, exception.getMessage(), exception);
        }
    }

    private void wrapVoid(VoidControllerCall call) {
        wrap(() -> {
            call.execute();
            return null;
        });
    }

    private interface ControllerCall<T> {
        T execute();
    }

    private interface VoidControllerCall {
        void execute();
    }
}
