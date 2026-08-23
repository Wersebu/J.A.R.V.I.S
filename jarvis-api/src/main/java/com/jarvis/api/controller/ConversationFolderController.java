package com.jarvis.api.controller;

import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.auth.ConversationFolder;
import com.jarvis.memory.auth.JarvisAuthRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/conversation-folders")
public class ConversationFolderController {

    private final JarvisAuthRepository repository;

    public ConversationFolderController(JarvisAuthRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ConversationFolder> list() {
        return repository.folders(CurrentUserContext.requiredUserId());
    }

    @PostMapping
    public ConversationFolder create(@RequestBody FolderRequest request) {
        return repository.createFolder(CurrentUserContext.requiredUserId(), request.name(), request.systemPrompt(), Instant.now());
    }

    @PatchMapping("/{folderId}")
    public ConversationFolder update(@PathVariable String folderId, @RequestBody FolderRequest request) {
        return repository.updateFolder(CurrentUserContext.requiredUserId(), folderId,
                request == null ? null : request.name(),
                request == null ? null : request.systemPrompt(),
                Instant.now());
    }

    @DeleteMapping("/{folderId}")
    public void delete(@PathVariable String folderId) {
        repository.deleteFolder(CurrentUserContext.requiredUserId(), folderId);
    }

    public record FolderRequest(String name, String systemPrompt) {
    }
}
