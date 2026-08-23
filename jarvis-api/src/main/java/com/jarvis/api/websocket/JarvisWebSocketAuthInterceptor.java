package com.jarvis.api.websocket;

import com.jarvis.memory.auth.JarvisAuthService;
import com.jarvis.memory.auth.UserAccount;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;
import java.util.Optional;

@Component
public class JarvisWebSocketAuthInterceptor implements HandshakeInterceptor {

    private final JarvisAuthService authService;

    public JarvisWebSocketAuthInterceptor(JarvisAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (authService.bootstrapRequired()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        Optional<UserAccount> user = authService.authenticateBearer(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        if (user.isEmpty()) {
            String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("access_token");
            user = authService.authenticateBearer(token == null ? "" : "Bearer " + token);
        }
        if (user.isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put("jarvis.userId", user.get().id());
        attributes.put("jarvis.user", user.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
