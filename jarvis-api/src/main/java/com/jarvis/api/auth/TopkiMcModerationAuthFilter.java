package com.jarvis.api.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Dedicated bearer-token guard for TopkiMC moderation routes.
 */
@Component
public class TopkiMcModerationAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final TopkiMcModerationAuthProperties properties;

    public TopkiMcModerationAuthFilter(TopkiMcModerationAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.equals("/v1/moderate") && !path.equals("/v1/moderate/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > properties.getMaxBodyBytes()) {
            write(response, HttpStatus.PAYLOAD_TOO_LARGE);
            return;
        }
        String configuredKey = properties.getApiKey();
        if (!validConfiguredKey(configuredKey) || !authorized(configuredKey, request.getHeader("Authorization"))) {
            write(response, HttpStatus.UNAUTHORIZED);
            return;
        }
        request.setAttribute("topkimc.moderation.keyId", fingerprint(configuredKey));
        filterChain.doFilter(request, response);
    }

    private boolean validConfiguredKey(String configuredKey) {
        return configuredKey != null && configuredKey.getBytes(StandardCharsets.UTF_8).length >= 32;
    }

    private boolean authorized(String configuredKey, String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) {
            return false;
        }
        byte[] expected = sha256(configuredKey);
        byte[] actual = sha256(authorization.substring(BEARER.length()));
        return MessageDigest.isEqual(expected, actual);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String fingerprint(String value) {
        byte[] digest = sha256(value);
        StringBuilder builder = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            builder.append("%02x".formatted(digest[i]));
        }
        return builder.toString();
    }

    private void write(HttpServletResponse response, HttpStatus status) throws IOException {
        response.setStatus(status.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Unauthorized\"}");
    }
}
