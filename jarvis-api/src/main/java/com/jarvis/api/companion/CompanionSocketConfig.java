package com.jarvis.api.companion;
import com.jarvis.api.websocket.JarvisWebSocketAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;
@Configuration
public class CompanionSocketConfig implements WebSocketConfigurer {
    private final CompanionSocket socket; private final JarvisWebSocketAuthInterceptor auth;
    public CompanionSocketConfig(CompanionSocket socket,JarvisWebSocketAuthInterceptor auth){this.socket=socket;this.auth=auth;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(socket,"/ws/companion").addInterceptors(auth);}
}
