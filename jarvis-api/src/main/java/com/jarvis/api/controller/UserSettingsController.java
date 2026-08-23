package com.jarvis.api.controller;

import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.auth.JarvisAuthRepository;
import com.jarvis.memory.auth.UserSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/users/me/settings")
public class UserSettingsController {

    private final JarvisAuthRepository repository;

    public UserSettingsController(JarvisAuthRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public UserSettings get() {
        return repository.settings(CurrentUserContext.requiredUserId());
    }

    @PutMapping
    public UserSettings update(@RequestBody UserSettingsRequest request) {
        String prompt = request == null ? "" : request.globalPrompt();
        return repository.saveSettings(CurrentUserContext.requiredUserId(), prompt, Instant.now());
    }

    public record UserSettingsRequest(String globalPrompt) {
    }
}
