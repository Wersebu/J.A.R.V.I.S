package com.jarvis.api.auth;

import com.jarvis.common.auth.CurrentUserContext;
import com.jarvis.memory.auth.AuthenticatedSession;
import com.jarvis.memory.auth.JarvisAuthService;
import com.jarvis.memory.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final JarvisAuthService authService;

    public AuthController(JarvisAuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/bootstrap-status")
    public BootstrapStatus bootstrapStatus() {
        return new BootstrapStatus(authService.bootstrapRequired());
    }

    @PostMapping("/bootstrap")
    public AuthResponse bootstrap(@RequestBody AuthRequest request) {
        try {
            return AuthResponse.from(authService.bootstrap(request.email(), request.password(), request.displayName()));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody AuthRequest request) {
        try {
            return AuthResponse.from(authService.login(request.email(), request.password()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password.", exception);
        }
    }

    @PostMapping("/logout")
    public void logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        authService.logout(authorization);
    }

    @GetMapping("/me")
    public UserResponse me(HttpServletRequest request) {
        Object user = request.getAttribute("jarvis.user");
        if (user instanceof UserAccount account) {
            return UserResponse.from(account);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required.");
    }

    public record BootstrapStatus(boolean bootstrapRequired) {
    }

    public record AuthRequest(String email, String password, String displayName) {
    }

    public record AuthResponse(String accessToken, UserResponse user, String expiresAt) {
        static AuthResponse from(AuthenticatedSession session) {
            return new AuthResponse(session.token(), UserResponse.from(session.user()), session.expiresAt().toString());
        }
    }

    public record UserResponse(String id, String email, String displayName, String role) {
        static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.email(), user.displayName(), user.role());
        }
    }
}
