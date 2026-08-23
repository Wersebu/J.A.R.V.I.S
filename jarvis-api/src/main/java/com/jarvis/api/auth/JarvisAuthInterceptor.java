package com.jarvis.api.auth;

import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.auth.JarvisAuthService;
import com.jarvis.memory.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

@Component
public class JarvisAuthInterceptor implements HandlerInterceptor {

    private final JarvisAuthService authService;

    public JarvisAuthInterceptor(JarvisAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();
        if (isPublic(path)) {
            return true;
        }
        if (authService.bootstrapRequired()) {
            writeUnauthorized(response, "Bootstrap required.");
            return false;
        }
        Optional<UserAccount> user = authService.authenticateBearer(request.getHeader("Authorization"));
        if (user.isEmpty()) {
            writeUnauthorized(response, "Authentication required.");
            return false;
        }
        CurrentUserContext.set(user.get().id());
        request.setAttribute("jarvis.user", user.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) {
        CurrentUserContext.clear();
    }

    private boolean isPublic(String path) {
        return path.equals("/api/v1/auth/bootstrap-status")
                || path.equals("/api/v1/auth/bootstrap")
                || path.equals("/api/v1/auth/login")
                || path.equals("/api/health")
                || path.startsWith("/actuator/")
                || path.equals("/actuator");
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "") + "\"}");
    }
}
