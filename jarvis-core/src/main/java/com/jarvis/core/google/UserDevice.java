package com.jarvis.core.google;

import com.jarvis.api.service.WindowsCodingBridgeGateway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What only the user's own device can do for Core: show a Google consent page and catch its one-time code, and ask
 * the user to approve a change (adding a calendar event...). The bridge reaches only the PC of the user the request
 * runs for, so a decision always comes from that person; the model can neither answer it nor skip it.
 */
@Component
public class UserDevice {

    private static final Duration CONSENT_WAIT = Duration.ofSeconds(320);
    private static final Duration APPROVAL_WAIT = Duration.ofSeconds(200);
    private final WindowsCodingBridgeGateway devices;
    private final GoogleOAuth oauth;

    @Autowired
    public UserDevice(ObjectProvider<WindowsCodingBridgeGateway> devices, GoogleOAuth oauth) {
        this(devices.getIfAvailable(), oauth);
    }

    public UserDevice(WindowsCodingBridgeGateway devices, GoogleOAuth oauth) {
        this.devices = devices;
        this.oauth = oauth;
    }

    private void requireDevice(String purpose) {
        if (devices == null || !"CONNECTED".equals(devices.codingStatus())) {
            throw new GoogleException(purpose + " needs the Jarvis Windows app running on the user's PC and logged in to this "
                    + "Jarvis account (the phone app will be able to do this later). Tell the user.");
        }
    }

    /**
     * Consent without a public Jarvis address: the user's PC opens Google's page and catches the one-time code on
     * 127.0.0.1. Core keeps the PKCE verifier and exchanges the code itself, so no token ever reaches the PC.
     */
    public void connectGoogle(String userId, GoogleService service) {
        GoogleOAuth.Authorization authorization = oauth.begin(service);
        requireDevice("Connecting " + service.label());
        Map<String, Object> loopback = new LinkedHashMap<>();
        loopback.put("authUrl", authorization.url());
        loopback.put("port", oauth.callbackPort());
        loopback.put("state", authorization.state());
        loopback.put("timeoutSeconds", 300);
        Map<String, Object> answer = devices.codingRequest("pc_oauth_loopback", loopback, CONSENT_WAIT);
        String error = String.valueOf(answer == null ? "" : answer.getOrDefault("error", "")).strip();
        String code = String.valueOf(answer == null ? "" : answer.getOrDefault("code", "")).strip();
        if (!error.isEmpty() || code.isEmpty()) {
            throw new GoogleException("Google consent was not completed (" + (error.isEmpty() ? "no answer" : error)
                    + "). Nothing was connected; the user can ask again.");
        }
        oauth.complete(userId, authorization, code);
    }

    /**
     * Shows an approval window on the user's PC and waits for the answer.
     *
     * @param title short question, e.g. "Jarvis chce dodać wydarzenie do kalendarza"
     * @param detail exactly what will change
     * @return true only when the user clicked allow
     */
    public boolean approve(String title, String detail) {
        requireDevice("Changing the calendar");
        Map<String, Object> answer = devices.codingRequest("pc_approval", Map.of("title", title, "detail", detail, "kind", "calendar"),
                APPROVAL_WAIT);
        return answer != null && Boolean.TRUE.equals(answer.get("approved"));
    }
}
