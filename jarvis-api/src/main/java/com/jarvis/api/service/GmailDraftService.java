package com.jarvis.api.service;

import java.util.Map;

/** Authenticated Gmail draft actions requested by the user's UI. */
public interface GmailDraftService {

    Map<String, Object> sendApprovedDraft(String userId, String draftId, String to, String cc, String subject, String body);

    Map<String, Object> discardDraft(String userId, String draftId);
}
