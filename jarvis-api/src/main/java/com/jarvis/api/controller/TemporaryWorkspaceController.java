package com.jarvis.api.controller;

import com.jarvis.api.service.TemporaryWorkspaceService;
import com.jarvis.common.dto.AttachmentMetadata;
import com.jarvis.common.dto.TemporaryWorkspaceMetadata;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * REST endpoints for temporary workspaces used by chat attachments.
 */
@RestController
public class TemporaryWorkspaceController {

    private final TemporaryWorkspaceService workspaceService;

    /**
     * Creates the controller.
     *
     * @param workspaceService temporary workspace service
     */
    public TemporaryWorkspaceController(TemporaryWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Creates an isolated temporary workspace.
     *
     * @param conversationId optional conversation identifier
     * @return workspace metadata
     */
    @PostMapping(path = "/api/v1/workspaces", produces = MediaType.APPLICATION_JSON_VALUE)
    public TemporaryWorkspaceMetadata createWorkspace(@RequestParam(required = false) String conversationId) {
        return workspaceService.createWorkspace(conversationId);
    }

    /**
     * Uploads one or more files into the workspace input area.
     *
     * @param workspaceId workspace identifier
     * @param conversationId optional conversation identifier
     * @param files uploaded files
     * @return stored attachment metadata
     */
    @PostMapping(path = "/api/v1/workspaces/{workspaceId}/attachments",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<AttachmentMetadata> upload(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String conversationId,
            @RequestPart("files") List<MultipartFile> files
    ) {
        try {
            return workspaceService.storeInput(workspaceId, conversationId, files);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.INSUFFICIENT_STORAGE, exception.getMessage(), exception);
        }
    }

    /**
     * Returns metadata for a temporary workspace.
     *
     * @param workspaceId workspace identifier
     * @return metadata
     */
    @GetMapping(path = "/api/v1/workspaces/{workspaceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public TemporaryWorkspaceMetadata metadata(@PathVariable String workspaceId) {
        return workspaceService.metadata(workspaceId);
    }

    /**
     * Deletes a temporary workspace.
     *
     * @param workspaceId workspace identifier
     */
    @DeleteMapping(path = "/api/v1/workspaces/{workspaceId}")
    public void deleteWorkspace(@PathVariable String workspaceId) {
        workspaceService.deleteWorkspace(workspaceId);
    }
}
