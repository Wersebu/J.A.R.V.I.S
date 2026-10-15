package com.jarvis.api.controller;

import com.jarvis.api.service.GmailDraftService;
import com.jarvis.common.auth.CurrentUserContext;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** UI-only send/discard actions for Gmail drafts. User identity always comes from the authenticated session. */
@RestController
@RequestMapping("/api/v1/gmail/drafts")
public class GmailDraftController {

    private final GmailDraftService drafts;

    public GmailDraftController(GmailDraftService drafts) {
        this.drafts = drafts;
    }

    @PostMapping("/{draftId}/send")
    public Map<String, Object> send(@PathVariable String draftId, @RequestBody SendDraftRequest request) {
        return drafts.sendApprovedDraft(CurrentUserContext.requireAuthenticatedUserId(), draftId,
                request.to(), request.cc(), request.subject(), request.body());
    }

    @PostMapping("/{draftId}/discard")
    public Map<String, Object> discard(@PathVariable String draftId) {
        return drafts.discardDraft(CurrentUserContext.requireAuthenticatedUserId(), draftId);
    }

    public record SendDraftRequest(String to, String cc, String subject, String body) {
    }
}
